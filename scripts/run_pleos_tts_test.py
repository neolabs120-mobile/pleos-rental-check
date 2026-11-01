"""Test return reminders with the real Pleos TTS service and capture only emulator output audio."""
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from uuid import uuid4
import argparse, array, json, math, os, queue, secrets, subprocess, sys, threading, time, wave
import httpx
from run_rental_e2e import Console, live_fingerprint, preferences, private_json, wait_for


# Decode the SDK's local protobuf stream without generating bindings or installing tools into the live application environment.
def varint(data, offset=0):
    value=shift=0
    while True:
        byte=data[offset];offset+=1;value|=(byte & 127)<<shift
        if byte<128:return value,offset
        shift+=7


# Extract just AudioPacket.audio; format and timestamp remain service metadata rather than microphone input.
def audio_bytes(packet):
    offset=0
    while offset<len(packet):
        tag,offset=varint(packet,offset);field,kind=tag>>3,tag & 7
        if kind==0:_,offset=varint(packet,offset)
        elif kind==2:
            size,offset=varint(packet,offset);value=packet[offset:offset+size];offset+=size
            if field==3:return value
        elif kind in (1,5):offset+=8 if kind==1 else 4
        else:raise ValueError('Unexpected protobuf wire type')
    return b''


# Authenticate with the running AVD's own token and read its output mix; this never requests or records the host microphone.
class OutputAudio:
    # Keep the authenticated stream and a bounded packet buffer private to this one diagnostic capture.
    def __init__(self, registry, destination):
        import grpc
        values=dict(line.split('=',1) for line in registry.read_text().splitlines() if '=' in line)
        values={k.strip():v.strip() for k,v in values.items()}
        self.channel=grpc.insecure_channel('127.0.0.1:'+values['grpc.port'])
        self.call=self.channel.unary_stream('/android.emulation.control.EmulatorController/streamAudio',request_serializer=lambda b:b,response_deserializer=lambda b:b)(
            b'\x08\xc4\xd8\x02\x10\x01\x18\x01',metadata=(('authorization','Bearer '+values['grpc.token']),),timeout=45)
        self.destination=destination;self.packets=[];self.error=None
        self.worker=threading.Thread(target=self.drain,daemon=True);self.worker.start()

    # Collect only the emulator's PCM16 stereo output while the first known Korean reminder is playing.
    def drain(self):
        try:
            for packet in self.call:
                self.packets.append(audio_bytes(packet))
                if sum(map(len,self.packets))>44100*4*40:break
        except Exception as error:
            if not self.call.cancelled():self.error=str(error)

    # Store a playable WAV and measured nonzero samples, distinguishing physical output from merely successful SDK method calls.
    def finish(self):
        self.call.cancel();self.worker.join(timeout=5);self.channel.close();pcm=b''.join(self.packets)
        with wave.open(str(self.destination),'wb') as output:
            output.setnchannels(2);output.setsampwidth(2);output.setframerate(44100);output.writeframes(pcm)
        samples=array.array('h',pcm)
        if sys.byteorder!='little':samples.byteswap()
        peak=max(map(abs,samples),default=0);rms=math.sqrt(sum(value*value for value in samples)/max(1,len(samples)))
        return {'source':'authenticated emulator streamAudio output only','sampleRate':44100,'channels':2,
                'durationSeconds':round(len(pcm)/(44100*4),3),'pcmPeak':peak,'pcmRms':round(rms,3),'error':self.error}


# Exercise an isolated reservation through production controller events and genuine SDK callbacks, then prove the user's existing state was preserved.
def run(args):
    os.umask(0o077);work=args.work_dir.resolve();work.mkdir(parents=True,exist_ok=True)
    adb=[str(args.adb),'-s',args.serial];base=f'http://127.0.0.1:{args.port}'
    avd=subprocess.run(adb+['emu','avd','name'],capture_output=True,text=True,check=True).stdout
    if 'Pleos_Connect_2_1_0' not in avd:raise RuntimeError('Unrecognized AVD')
    before_raw,before_prefs=preferences(adb);(work/'preferences-before.tar').write_bytes(before_raw)
    before_live=live_fingerprint(args.live_data_dir);prefix='tts_'+uuid4().hex[:12];key=secrets.token_urlsafe(32)
    server=native=console=audio=None;report={};error=None;lines=[];stages=[];audio_report=None
    server_log=open(work/'server.log','w')
    try:
        installed=subprocess.run(adb+['shell','pm','path','kr.taeuk.pleos.rentalcheck'],capture_output=True,text=True,check=True).stdout.strip().removeprefix('package:')
        subprocess.run(adb+['pull',installed,str(work/'installed-before.apk')],capture_output=True,check=True)
        subprocess.run(adb+['install','-r',str(args.apk)],capture_output=True,check=True)
        env=os.environ.copy();env.update(RENTAL_DEVICE_TOKEN=key,RENTAL_MAIL_MODE='file',PUBLIC_BASE_URL=base)
        server=subprocess.Popen([sys.executable,str(args.server_source/'main.py'),'--port',str(args.port),'--data-dir',str(work/'data'),'--allow-emulator-test-vin'],env=env,stdout=server_log,stderr=subprocess.STDOUT)
        wait_for('isolated server',lambda:httpx.get(base+'/health',timeout=2).status_code==200)
        console=Console('http://127.0.0.1:8088');private_json(work/'console-before.json',console.before);console.prepare()
        email='pleos-tts-'+uuid4().hex[:10]+'@example.test';password='LocalTest-'+secrets.token_urlsafe(24)
        client=httpx.Client(base_url=base,timeout=15)
        response=client.post('/api/device/bookings',headers={'Authorization':'Bearer '+key},json={'email':email,'vin':'TEST0000000000001','vehicleName':'Pleos 음성 안내 시험','isDemo':True});response.raise_for_status();booking=response.json()
        response=client.post('/api/auth/signup',json={'nickname':'음성 안내 시험','email':email,'password':password,'termsAccepted':True,'privacyAccepted':True});response.raise_for_status();challenge=response.json()
        mail=wait_for('file verification mail',lambda:next((json.loads(p.read_text()) for p in (work/'data/mail-outbox').glob('*.json') if json.loads(p.read_text()).get('email')==email and json.loads(p.read_text()).get('purpose')=='signup'),None))
        response=client.post('/api/auth/verify',json={'challengeId':challenge['challengeId'],'code':mail['code']});response.raise_for_status()
        response=client.post('/api/auth/login',json={'email':email,'password':password});response.raise_for_status();token=response.json()['token']
        private_json(work/'fixture.json',{'prefix':prefix,'email':email,'password':password,'token':token,'booking':booking})
        subprocess.run(adb+['reverse',f'tcp:{args.port}',f'tcp:{args.port}'],capture_output=True,check=True)
        subprocess.run(adb+['install','-r',str(args.test_apk)],capture_output=True,check=True)
        command="run-as kr.taeuk.pleos.rentalcheck --user 10 sh -c 'umask 077; mkdir -p files; cat > files/"+prefix+"-config.json'"
        subprocess.run(adb+['shell',command],input=json.dumps({'serverUrl':base,'deviceToken':key}),text=True,capture_output=True,check=True)
        native=subprocess.Popen(adb+['shell','am','instrument','--user','10','-w','-r','-e','prefix',prefix,'kr.taeuk.pleos.rentalcheck.test/kr.taeuk.pleos.rentalcheck.ReturnSeatSpeechFlowRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        messages=queue.Queue()
        # Drain native stages without exposing the QR approval token in public progress or the final report.
        def drain():
            for line in native.stdout:messages.put(line)
            messages.put(None)
        threading.Thread(target=drain,daemon=True).start();deadline=time.monotonic()+360
        while time.monotonic()<deadline:
            try:line=messages.get(timeout=1)
            except queue.Empty:continue
            if line is None:break
            lines.append(line)
            if not line.startswith('INSTRUMENTATION_STATUS: ttsStage='):continue
            stage=json.loads(line.split('=',1)[1]);name=stage['name'];stages.append(name);print('STAGE:',name,flush=True)
            if name=='PAIRING':
                pair_token=parse_qs(urlparse(stage['pairingUrl']).fragment)['pair'][0]
                response=client.post('/api/account/pairings/approve',headers={'Authorization':'Bearer '+token},json={'token':pair_token,'bookingId':booking['bookingId']});response.raise_for_status()
            elif name=='OPEN_WINDOW_OCCUPIED':
                console.put('/vehicle/seat',kind='occupied',area='rear_right',value=True);console.put('/vehicle/body',window_rr=30)
            elif name=='CLOSE_WINDOW':
                audio=OutputAudio(args.grpc_registry,work/'pleos-return-reminder.wav');time.sleep(.4);console.put('/vehicle/body',window_rr=0)
            elif name=='FIRST_STARTED':
                dump=subprocess.run(adb+['shell','dumpsys','audio'],capture_output=True,text=True,check=True,timeout=15).stdout;(work/'audio-playing.txt').write_text(dump)
            elif name=='CHANGE_SEATS':
                audio_report=audio.finish();audio=None
                console.put('/vehicle/seat',kind='occupied',area='rear_right',value=False);console.put('/vehicle/seat',kind='occupied',area='passenger',value=True)
        else:raise RuntimeError('TTS flow exceeded six minutes')
        native.wait(timeout=10);raw=''.join(lines);(work/'native-private.log').write_text(raw)
        result_line=next((line for line in lines if line.startswith('INSTRUMENTATION_RESULT: ttsFlow=')),None)
        if not result_line or 'INSTRUMENTATION_CODE: -1' not in raw:raise RuntimeError('Native TTS flow failed; inspect native-private.log')
        result=json.loads(result_line.split('=',1)[1]);private_json(work/'native-result.json',result)
        assert all(result['checks'].values()) and result['first']['starts']==1 and result['first']['completions']==1
        assert audio_report and audio_report['error'] is None and audio_report['pcmPeak']>0 and audio_report['pcmRms']>0
        response=client.get('/api/account/rentals/'+result['recordId'],headers={'Authorization':'Bearer '+token});response.raise_for_status();record=response.json()
        private_json(work/'server-record.json',record)
        report={'passed':True,'stages':stages,'checks':result['checks'],'firstSpeech':result['first'],'finalSpeech':result['final'],
                'recordId':result['recordId'],'stage':result['stage'],'mode':result['mode'],'locale':result['locale'],'audio':audio_report,
                'method':'Console signals → actual Vehicle SDK → production return controller → Pleos TTS service → emulator PCM output',
                'vinMode':'known Debug AVD explicit test VIN; real-car validation unchanged'}
    except Exception as caught:
        error=caught;report={'passed':False,'error':str(caught),'stages':stages}
    finally:
        if audio:report['audio']=audio.finish()
        (work/'native-private.log').write_text(''.join(lines))
        if native and native.poll() is None:subprocess.run(adb+['shell','am','force-stop','--user','10','kr.taeuk.pleos.rentalcheck'],capture_output=True)
        if console:
            try:report.update(console.restore());report['consoleSignalsRestored']=True
            except Exception as caught:report['restoreError']=str(caught);error=error or caught
        after_raw,after_prefs=preferences(adb);(work/'preferences-after.tar').write_bytes(after_raw)
        report['originalPreferenceFilesPreserved']=all(after_prefs.get(k)==v for k,v in before_prefs.items())
        report['originalPreferenceFileCount']=len(before_prefs);report['liveUserDatabasePreserved']=before_live==live_fingerprint(args.live_data_dir)
        if not report['originalPreferenceFilesPreserved'] or not report['liveUserDatabasePreserved']:error=error or RuntimeError('Original user state changed')
        subprocess.run(adb+['uninstall','kr.taeuk.pleos.rentalcheck.test'],capture_output=True)
        subprocess.run(adb+['reverse','--remove',f'tcp:{args.port}'],capture_output=True)
        subprocess.run(adb+['reverse','tcp:8787','tcp:8787'],capture_output=True)
        subprocess.run(adb+['shell','am','start','--user','10','-n','kr.taeuk.pleos.rentalcheck/.MainActivity'],capture_output=True)
        if server:server.terminate();server.wait(timeout=10)
        server_log.close();report['passed']=report.get('passed',False) and error is None;private_json(work/'report.json',report)
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    if error:raise SystemExit(1)


# Require explicit isolated build/runtime paths and an existing authenticated AVD endpoint instead of selecting or weakening another environment.
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['work-dir','server-source','apk','test-apk','live-data-dir','grpc-registry']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--serial',default='emulator-5554');parser.add_argument('--port',type=int,default=8788)
    parser.add_argument('--adb',type=Path,default=Path.home() / 'Library/Android/sdk/platform-tools/adb')
    args=parser.parse_args()
    if args.port==8787:parser.error('Use an isolated test server port')
    run(args)


if __name__=='__main__':main()
