"""Run an isolated real-AVD/Flutter rental round trip; never reset the installed app or the user's server."""
from pathlib import Path
from uuid import uuid4
import argparse,hashlib,json,os,queue,secrets,sqlite3,subprocess,sys,threading,time
import httpx
from PIL import Image,ImageDraw


# Atomically keep account tokens and pairing URLs private while the host and Flutter client coordinate one test run.
def private_json(path,value):
    temporary=path.with_suffix(path.suffix+'.next')
    descriptor=os.open(temporary,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
    with os.fdopen(descriptor,'w') as stream:json.dump(value,stream,ensure_ascii=False,indent=2)
    os.replace(temporary,path)


# Preserve original preference bytes without decoding the user's encrypted settings or touching their current rental.
def preferences(adb):
    import io,tarfile
    raw=subprocess.run(adb+['exec-out','run-as','kr.taeuk.pleos.rentalcheck','--user','10','tar','-cf','-','shared_prefs'],capture_output=True,check=True).stdout
    archive=tarfile.open(fileobj=io.BytesIO(raw))
    return raw,{entry.name:archive.extractfile(entry).read() for entry in archive.getmembers() if entry.isfile()}


# Hash live business rows read-only so an isolated test must prove that existing accounts, reservations and evidence stayed unchanged.
def live_fingerprint(directory):
    with sqlite3.connect('file:'+str(directory/'rentals.sqlite3')+'?mode=ro',uri=True) as connection:
        rows={name:connection.execute('SELECT * FROM '+name+' ORDER BY 1').fetchall() for name in ['users','bookings','pairings','rentals','photos','checks']}
    return hashlib.sha256(repr(rows).encode()).hexdigest()


# Bound service startup and asynchronous completion; elapsed time alone is never a successful test result.
def wait_for(label,probe,timeout=45):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            value=probe()
            if value:return value
        except (httpx.HTTPError,FileNotFoundError):pass
        time.sleep(.3)
    raise RuntimeError('Timed out: '+label)


# Read only public state and published control definitions from the AVD's own Console protocol, then restore every signal used by this test.
class Console:
    # Capture the simulator baseline and lease its public test bridge without changing the application account or SDK adapter.
    def __init__(self,base):
        self.base=base;self.client='rental-e2e-'+uuid4().hex;self.stop=threading.Event()
        self.before=self.request('GET','/vehicle/state');self.catalog=self.request('GET','/vehicle/catalog')
        self.worker=threading.Thread(target=self.heartbeat,daemon=True);self.worker.start()

    # Maintain the same documented client lease that enables the Console's simulation bridge; no browser DOM or app-private API is used.
    def heartbeat(self):
        while not self.stop.is_set():
            try:self.request('POST','/browser/session',{'client':self.client})
            except httpx.HTTPError:pass
            self.stop.wait(.7)

    # Send bounded requests only to the explicitly selected local AVD Console, separate from the rental application's API.
    def request(self,method,path,params=None):
        response=httpx.request(method,self.base+path,params=params,timeout=15);response.raise_for_status();return response.json()

    # Publish simulation controls through endpoints advertised by the Console and let the application read them through the genuine SDK.
    def put(self,path,**params):
        return self.request('PUT',path,{k:(str(v).lower() if isinstance(v,bool) else int(v) if isinstance(v,float) and v.is_integer() else v) for k,v in params.items()})

    # Establish a known, parked and closed starting state only after the Console says the actual emulator bridge is active.
    def prepare(self):
        wait_for('Console emulator lease',lambda:self.request('GET','/vehicle/state')['body']['emulator_active'])
        self.put('/vehicle/hvac/mode',mode='subscribe_hvac_base')
        self.put('/vehicle/gear',gear='Park')
        self.put('/vehicle/body',window_fl=0,window_fr=0,window_rl=0,window_rr=0,door_fl=False,door_fr=False,door_rl=False,door_rr=False,trunk=False,frunk=False)
        for area in self.before['seat']['occupied']:self.put('/vehicle/seat',kind='occupied',area=area,value=False)

    # Restore resettable controls while honoring the Console's explicit rule that a cumulative odometer cannot decrease.
    def restore(self):
        before=self.before;body=before['body'];windows=body['windows'];doors=body['doors'];closures=body['closures']
        try:
            self.put('/vehicle/body',**{f'window_{key}':windows[area] for key,area in [('fl','front_left'),('fr','front_right'),('rl','rear_left'),('rr','rear_right')]},
                     **{f'door_{key}':doors[area] for key,area in [('fl','front_left'),('fr','front_right'),('rl','rear_left'),('rr','rear_right')]},trunk=closures['trunk'],frunk=closures['frunk'])
            soc=before['ev_battery'].get('state_of_charge')
            if soc is not None:self.put('/vehicle/ev-battery',field='soc',value=soc)
            gear=(before['gear'].get('actual') or {}).get('value','Park');self.put('/vehicle/gear',gear=gear)
            self.put('/vehicle/hvac/mode',mode=before['hvac']['dds_mode'])
            for area,value in before['seat']['occupied'].items():self.put('/vehicle/seat',kind='occupied',area=area,value=value)
            after=self.request('GET','/vehicle/state')
            assert after['seat']['occupied']==before['seat']['occupied']
            assert after['body']['windows']==windows and after['body']['doors']==doors
            assert after['body']['closures']['trunk']==closures['trunk']
            if soc is not None:assert after['ev_battery']['state_of_charge']==soc
            assert after['odometer']['traveled_distance_km']>=before['odometer']['traveled_distance_km']
            return {'odometerBeforeKm':before['odometer']['traveled_distance_km'],
                    'odometerAfterKm':after['odometer']['traveled_distance_km'],
                    'odometerPolicy':'cumulative counter retained; simulator rejects decreases','seatSignalsRestored':True}
        finally:
            self.stop.set();self.worker.join(timeout=3)
            self.request('POST','/browser/session',{'client':self.client,'closing':1})


# Run native SDK/controller and Flutter API clients concurrently, advancing vehicle signals only after each guarded application stage is observed.
def run(args):
    work=args.work_dir.resolve();work.mkdir(parents=True,exist_ok=True);os.umask(0o077)
    adb=[str(args.adb),'-s',args.serial];base='http://127.0.0.1:'+str(args.port)
    avd=subprocess.run(adb+['emu','avd','name'],capture_output=True,text=True,check=True).stdout
    if 'Pleos_Connect_2_1_0' not in avd:raise RuntimeError('Refusing to change signals on an unrecognized AVD')
    prefix='e2e_'+uuid4().hex[:12];key=secrets.token_urlsafe(32);fixture_file=work/'fixture.json'
    server=native=phone=console=None;report={};error=None
    before_raw,before_preferences=preferences(adb);(work/'preferences-before.tar').write_bytes(before_raw)
    before_live=live_fingerprint(args.live_data_dir)
    log=open(work/'server.log','w');native_lines=[];stages=[]
    try:
        if args.apk:
            installed=subprocess.run(adb+['shell','pm','path','kr.taeuk.pleos.rentalcheck'],capture_output=True,text=True,check=True).stdout.strip().removeprefix('package:')
            subprocess.run(adb+['pull',installed,str(work/'installed-before.apk')],capture_output=True,check=True)
            subprocess.run(adb+['install','-r',str(args.apk)],capture_output=True,check=True)
        env=os.environ.copy();env.update(RENTAL_DEVICE_TOKEN=key,RENTAL_MAIL_MODE='file',PUBLIC_BASE_URL=base)
        server=subprocess.Popen([sys.executable,str(args.server_source/'main.py'),'--port',str(args.port),'--data-dir',str(work/'data'),'--allow-emulator-test-vin','--flutter-web-dir',str(args.web_dir)],env=env,stdout=log,stderr=subprocess.STDOUT)
        wait_for('isolated rental server',lambda:httpx.get(base+'/health',timeout=2).status_code==200)
        console=Console('http://127.0.0.1:8088');private_json(work/'console-before.json',console.before);console.prepare()
        email='rental-e2e-'+uuid4().hex[:10]+'@example.test';password='LocalTest-'+secrets.token_urlsafe(24)
        response=httpx.post(base+'/api/device/bookings',headers={'Authorization':'Bearer '+key},json={'email':email,'vin':'TEST0000000000001','vehicleName':'자동 인수·이용·반납 시험 차량','isDemo':True},timeout=15);response.raise_for_status();booking=response.json()
        photos={};requests={}
        for category,color in [('windows','#176b65'),('belongings','#395a8f'),('exterior','#8c6b31')]:
            image=Image.new('RGB',(640,360),color);ImageDraw.Draw(image).text((20,30),'AUTOMATED RENTAL FLOW TEST\n'+category.upper()+'\nGENERATED FIXTURE - NOT A REAL VEHICLE PHOTO',fill='white')
            path=work/(category+'.png');image.save(path);photos[category]=str(path);requests[category]=str(uuid4())
        fixture={'baseUrl':base,'workDir':str(work),'dataDir':str(work/'data'),'email':email,'password':password,'bookingId':booking['bookingId'],'photos':photos,'requestIds':requests}
        private_json(fixture_file,fixture)
        subprocess.run(adb+['reverse','tcp:'+str(args.port),'tcp:'+str(args.port)],capture_output=True,check=True)
        subprocess.run(adb+['install','-r',str(args.test_apk)],capture_output=True,check=True)
        config=json.dumps({'serverUrl':base,'deviceToken':key})
        command="run-as kr.taeuk.pleos.rentalcheck --user 10 sh -c 'umask 077; mkdir -p files; cat > files/"+prefix+"-config.json'"
        subprocess.run(adb+['shell',command],input=config,text=True,capture_output=True,check=True)
        native=subprocess.Popen(adb+['shell','am','instrument','--user','10','-w','-r','-e','prefix',prefix,'kr.taeuk.pleos.rentalcheck.test/kr.taeuk.pleos.rentalcheck.FullRentalFlowRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        messages=queue.Queue()
        # Drain native progress without printing QR tokens; a sentinel makes a crashed instrumentation process fail immediately.
        def drain():
            for line in native.stdout:messages.put(line)
            messages.put(None)
        threading.Thread(target=drain,daemon=True).start()
        deadline=time.monotonic()+480
        while time.monotonic()<deadline:
            try:line=messages.get(timeout=1)
            except queue.Empty:continue
            if line is None:break
            native_lines.append(line)
            if not line.startswith('INSTRUMENTATION_STATUS: rentalStage='):continue
            stage=json.loads(line.split('=',1)[1]);name=stage['name'];stages.append(name);print('STAGE:',name,flush=True)
            if name=='PAIRING':
                fixture['pairingUrl']=stage['pairingUrl'];private_json(fixture_file,fixture)
                phone_env=os.environ.copy();phone_env.update(RENTAL_E2E_FIXTURE=str(fixture_file),CI='true',FLUTTER_SUPPRESS_ANALYTICS='true',DART_SUPPRESS_ANALYTICS='true',XDG_CONFIG_HOME=str(args.flutter_config))
                phone=subprocess.Popen([str(args.flutter),'test','--no-pub','--dart-define=RENTAL_EMULATOR_E2E=true','test/emulator_rental_e2e_test.dart'],cwd=args.flutter_project,env=phone_env,stdout=open(work/'flutter-e2e.log','w'),stderr=subprocess.STDOUT)
            elif name=='PRACTICE_OPEN':console.put('/vehicle/body',window_fl=30)
            elif name=='PRACTICE_CLOSE':console.put('/vehicle/body',window_fl=0)
            elif name=='SPACES_OPEN':console.put('/vehicle/body',door_rr=True,trunk=True)
            elif name=='SPACES_CLOSE':console.put('/vehicle/body',door_rr=False,trunk=False)
            elif name=='DRIVE':
                console.put('/vehicle/gear',gear='Drive');console.put('/vehicle/odometer',traveled_distance_km=stage['pickupOdometerKm']+3)
                soc=console.before['ev_battery'].get('state_of_charge')
                if soc is not None:console.put('/vehicle/ev-battery',field='soc',value=max(0,soc-2))
                console.put('/vehicle/gear',gear='Park')
            elif name=='RETURN_OPEN_WINDOW':
                console.put('/vehicle/seat',kind='occupied',area='rear_right',value=True)
                console.put('/vehicle/body',window_rr=30)
            elif name=='RETURN_CLOSE_WINDOW':console.put('/vehicle/body',window_rr=0)
            elif name=='RETURN_SEATS_EMPTY':
                for area in console.before['seat']['occupied']:console.put('/vehicle/seat',kind='occupied',area=area,value=False)
            elif name=='RETURN_SEAT_CHANGE':
                for area in ['driver','passenger']:console.put('/vehicle/seat',kind='occupied',area=area,value=True)
        else:raise RuntimeError('Whole rental test exceeded eight minutes')
        native.wait(timeout=10);raw=''.join(native_lines);(work/'native-private.log').write_text(raw)
        result_line=next((s for s in native_lines if s.startswith('INSTRUMENTATION_RESULT: rentalE2e=')),None)
        if not result_line or 'INSTRUMENTATION_CODE: -1' not in raw:raise RuntimeError('Native rental scenario failed; inspect native-private.log')
        result=json.loads(result_line.split('=',1)[1]);private_json(work/'native-result.json',result)
        if phone is None or phone.wait(timeout=45)!=0:raise RuntimeError('Flutter client scenario failed; inspect flutter-e2e.log')
        phone_result=json.loads((work/'phone-result.json').read_text())
        assert result['recordId']==phone_result['recordId'] and result['receiptId']==phone_result['receiptId']
        assert all(result['checks'].values()) and all(phone_result['checks'].values()) and result['stage']=='RESULT'
        report={'passed':True,'stages':stages,'nativeChecks':result['checks'],'phoneChecks':phone_result['checks'],'recordId':result['recordId'],'receiptId':result['receiptId'],'distanceKm':result['distanceKm'],'usageMemory':result['memory'],'vinMode':'explicit Debug emulator test VIN; remaining values from actual SDK','method':'Console published simulation API → real AVD SDK/controller + production Flutter HTTP client','photoFixture':'three generated test images, not real vehicle photographs'}
    except Exception as caught:
        error=caught;report={'passed':False,'error':str(caught),'stages':stages}
    finally:
        if native and native.poll() is None:subprocess.run(adb+['shell','am','force-stop','--user','10','kr.taeuk.pleos.rentalcheck'],capture_output=True)
        if phone and phone.poll() is None:phone.terminate();phone.wait(timeout=10)
        if console:
            try:report.update(console.restore());report['resettableConsoleSignalsRestored']=True
            except Exception as caught:report['resettableConsoleSignalsRestored']=False;report['restoreError']=str(caught);error=error or caught
        after_raw,after_preferences=preferences(adb);(work/'preferences-after.tar').write_bytes(after_raw)
        report['originalPreferenceFilesPreserved']=all(after_preferences.get(k)==v for k,v in before_preferences.items())
        report['originalPreferenceFileCount']=len(before_preferences);report['liveUserDatabasePreserved']=before_live==live_fingerprint(args.live_data_dir)
        if not report['originalPreferenceFilesPreserved'] or not report['liveUserDatabasePreserved']:error=error or RuntimeError('Original user state changed during isolation test')
        subprocess.run(adb+['uninstall','kr.taeuk.pleos.rentalcheck.test'],capture_output=True)
        subprocess.run(adb+['reverse','--remove','tcp:'+str(args.port)],capture_output=True)
        subprocess.run(adb+['reverse','tcp:8787','tcp:8787'],capture_output=True)
        subprocess.run(adb+['shell','am','start','--user','10','-n','kr.taeuk.pleos.rentalcheck/.MainActivity'],capture_output=True)
        if server and (not args.keep_server or error):server.terminate();server.wait(timeout=10)
        report['passed']=report.get('passed',False) and error is None
        private_json(work/'report.json',report);log.close()
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    if error:raise SystemExit(1)


# Require explicit locations for the local build/runtime so the test never selects another emulator, app checkout or live database by guessing.
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['work-dir','server-source','web-dir','flutter-project','test-apk','live-data-dir','flutter-config']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--apk',type=Path,help='Update the production app with this APK after preserving the installed APK and original preferences')
    parser.add_argument('--serial',default='emulator-5554');parser.add_argument('--port',type=int,default=8788)
    parser.add_argument('--adb',type=Path,default=Path.home() / 'Library/Android/sdk/platform-tools/adb')
    parser.add_argument('--flutter',type=Path,default=Path('flutter'));parser.add_argument('--keep-server',action='store_true')
    args=parser.parse_args()
    if args.port==8787:parser.error('Use an isolated port, never the user server port 8787')
    run(args)


if __name__=='__main__':main()
