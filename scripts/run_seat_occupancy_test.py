"""Test real Pleos seat reads/callbacks and restore simulator seats without creating a rental or changing the user's preferences."""
from pathlib import Path
from uuid import uuid4
import argparse,importlib.util,json,os,queue,subprocess,threading,time


def run(args):
    # Reuse the existing public Console protocol and read-only preservation checks rather than creating a second simulation API.
    spec=importlib.util.spec_from_file_location('rental_e2e',Path(__file__).with_name('run_rental_e2e.py'))
    helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
    os.umask(0o077);work=args.work_dir.resolve();work.mkdir(parents=True,exist_ok=True)
    adb=[str(args.adb),'-s','emulator-5554'];package='kr.taeuk.pleos.rentalcheck'
    name=subprocess.run(adb+['emu','avd','name'],capture_output=True,text=True,check=True).stdout
    if 'Pleos_Connect_2_1_0' not in name:raise RuntimeError('Refusing an unrecognized AVD')
    before_raw,before_prefs=helper.preferences(adb);(work/'preferences-before.tar').write_bytes(before_raw)
    before_db=helper.live_fingerprint(args.live_data_dir)
    apk_path=subprocess.run(adb+['shell','pm','path',package],capture_output=True,text=True,check=True).stdout.strip().removeprefix('package:')
    subprocess.run(adb+['pull',apk_path,str(work/'installed-before.apk')],capture_output=True,check=True)
    prefix='seat_'+uuid4().hex[:12];console=native=None;report={};failure=None;lines=[];service_pid=None
    areas={'ROW_1_LEFT':'driver','ROW_1_RIGHT':'passenger','ROW_2_LEFT':'rear_left','ROW_2_RIGHT':'rear_right'}
    try:
        console=helper.Console('http://127.0.0.1:8088')
        helper.private_json(work/'console-before.json',console.before)
        feature=next(f for f in console.catalog['features'] if f['id']=='seat-occupancy')
        assert feature['write_endpoint']=='/vehicle/seat' and feature['control']['params']=={'kind':'occupied'}
        helper.wait_for('Console emulator lease',lambda:console.request('GET','/vehicle/state')['body']['emulator_active'])
        for area in areas.values():console.put('/vehicle/seat',kind='occupied',area=area,value=False)
        subprocess.run(adb+['install','-r',str(args.apk)],capture_output=True,check=True)
        subprocess.run(adb+['install','-r',str(args.test_apk)],capture_output=True,check=True)
        native=subprocess.Popen(adb+['shell','am','instrument','--user','10','-w','-r','-e','prefix',prefix,
            package+'.test/'+package+'.SeatOccupancyFlowRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        messages=queue.Queue()
        # Keep instrumentation output flowing so a dead process or a missing callback cannot silently hang the host indefinitely.
        def drain():
            for line in native.stdout:messages.put(line)
            messages.put(None)
        threading.Thread(target=drain,daemon=True).start();deadline=time.monotonic()+240
        while time.monotonic()<deadline:
            try:line=messages.get(timeout=1)
            except queue.Empty:continue
            if line is None:break
            lines.append(line)
            if not line.startswith('INSTRUMENTATION_STATUS: seatStage='):continue
            stage=json.loads(line.split('=',1)[1]);area=areas[stage['key'].split('.')[1]]
            if service_pid is None:service_pid=subprocess.run(adb+['shell','pidof','ai.pleos.playground.service.vehicle'],capture_output=True,text=True,check=True).stdout.strip()
            console.put('/vehicle/seat',kind='occupied',area=area,value=stage['occupied'])
            print(stage['name'],area,'Occupied' if stage['occupied'] else 'Empty',flush=True)
        else:raise RuntimeError('Seat test exceeded four minutes')
        native.wait(timeout=10);raw=''.join(lines)
        result=next((line for line in lines if line.startswith('INSTRUMENTATION_RESULT: seatResult=')),None)
        if result is None:raise RuntimeError('No native seat result; inspect native.log')
        report=json.loads(result.split('=',1)[1])
        assert report['passed'] and 'INSTRUMENTATION_CODE: -1' in raw,report
        tested=[sample for sample in report['samples'] if 'target' in sample]
        report['diagnosticCompleted']=True
        report['normalizedValuesMatchConsole']=all(next(r['value'] for r in s['readings'] if r['key']==s['target'])==str(s['consoleOccupied']).lower() for s in tested)
        report['rawEnumsMatchConsole']=all(next(r['rawValue'] for r in s['readings'] if r['key']==s['target'])==('2' if s['consoleOccupied'] else '1') for s in tested)
        report['sdkBooleanStillInverted']=all(next(r['sdkValue'] for r in s['readings'] if r['key']==s['target'])==str(not s['consoleOccupied']).lower() for s in tested)
        report['seatChangeCallbacksReceived']=all(s['callbacks']>0 for index,s in enumerate(tested) if index%3!=0)
        initial={r['key']:r['value'] for r in report['samples'][0]['readings']}
        report['areaIsolation']=all(all(r['value']==initial[r['key']] for r in s['readings'] if r['key']!=s['target']) for s in tested)
        report['gettersMatchLiveState']=all(next(r['value'] for r in s['readings'] if r['key']==s['target'])==s['liveValue'] for s in tested)
        report['transportPassed']=len(tested)==12 and report['seatChangeCallbacksReceived'] and report['areaIsolation'] and report['gettersMatchLiveState']
        service_after=subprocess.run(adb+['shell','pidof','ai.pleos.playground.service.vehicle'],capture_output=True,text=True,check=True).stdout.strip()
        report['vehicleServiceStable']=bool(service_pid) and service_pid==service_after
        report['method']='Published Console controls → real Pleos Vehicle Service raw enum and original SDK Boolean → normalized production adapter and assistance state'
        report['vinRequired']=False;report['createdRental']=False
    except Exception as error:
        failure=error;report.update(passed=False,error=str(error))
    finally:
        if native and native.poll() is None:
            subprocess.run(adb+['shell','am','force-stop','--user','10',package],capture_output=True)
            native.wait(timeout=15)
        (work/'native.log').write_text(''.join(lines))
        if console:
            try:
                for area,value in console.before['seat']['occupied'].items():console.put('/vehicle/seat',kind='occupied',area=area,value=value)
                after=console.request('GET','/vehicle/state');helper.private_json(work/'console-after.json',after)
                report['seatSignalsRestored']=after['seat']['occupied']==console.before['seat']['occupied']
                report['odometerPreserved']=after['odometer']['traveled_distance_km']==console.before['odometer']['traveled_distance_km']
            except Exception as error:failure=failure or error;report['restoreError']=str(error)
            finally:
                console.stop.set();console.worker.join(timeout=3)
                console.request('POST','/browser/session',{'client':console.client,'closing':1})
        after_raw,after_prefs=helper.preferences(adb);(work/'preferences-after.tar').write_bytes(after_raw)
        report['originalPreferencesPreserved']=all(after_prefs.get(k)==v for k,v in before_prefs.items())
        report['originalPreferenceCount']=len(before_prefs)
        report['liveDatabasePreserved']=before_db==helper.live_fingerprint(args.live_data_dir)
        subprocess.run(adb+['uninstall',package+'.test'],capture_output=True)
        preserved=all(report.get(k) is True for k in ['seatSignalsRestored','odometerPreserved','originalPreferencesPreserved','liveDatabasePreserved'])
        report['passed']=report.get('passed',False) and preserved and failure is None and report.get('normalizedValuesMatchConsole',False) and report.get('rawEnumsMatchConsole',False) and report.get('transportPassed',False) and report.get('vehicleServiceStable',False)
        helper.private_json(work/'report.json',report)
    print(json.dumps({k:v for k,v in report.items() if k!='samples'},indent=2))
    if not report['passed']:raise SystemExit(1)


def main():
    # Require explicit built APKs and preservation locations so a repeat cannot accidentally install a stale application or reset a live database.
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--test-apk',type=Path,required=True)
    parser.add_argument('--work-dir',type=Path,required=True);parser.add_argument('--live-data-dir',type=Path,required=True)
    parser.add_argument('--adb',type=Path,default=Path.home() / 'Library/Android/sdk/platform-tools/adb')
    run(parser.parse_args())


if __name__=='__main__':main()
