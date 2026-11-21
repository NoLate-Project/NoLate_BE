"""Owned bounded collector; never retry a provider error or touch unrelated processes."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time

root = Path(__file__).resolve().parent
evidence = Path('/private/tmp/nolate-eta-real-e2e.f73idd8i')
owner = json.loads((root / 'owned-parent.json').read_text())

def stop_owned_parent(reason):
    identity = subprocess.run(['ps','-p',str(owner['pid']),'-o','lstart='], capture_output=True,text=True)
    command = subprocess.run(['ps','-p',str(owner['pid']),'-o','args='], capture_output=True,text=True)
    if identity.returncode or command.returncode or identity.stdout.strip()!=owner['started'] or command.stdout.strip()!=owner['command']:
        return
    # SIGINT runs the runner's finally block, restoring its endpoint and stopping only owned children.
    os.kill(owner['pid'],signal.SIGINT)
    with (root / 'parent-provider-error-stop.json').open('x') as stream:
        json.dump({'reason':reason,'ownedPid':owner['pid'],'signal':'SIGINT_FOR_FINALLY_CLEANUP'},stream)

def provider_failed():
    for index in range(1,4):
        try: value=json.loads((evidence/f'evaluation-{index}.json').read_text())
        except (OSError,ValueError): continue
        if value.get('providerFailures') or any(x['outcome']!='success' for x in value.get('providerWire',[])):
            return True
        errors=(value.get('walkingRefinement')or{}).get('failureCounts')or{}
        if any(errors.get(c,0) for c in ('HTTP_AUTH','HTTP_QUOTA','PROVIDER_APPLICATION_ERROR')):
            return True
    return False

java='/Users/mac/.sdkman/candidates/java/21.0.2-open/bin/java'
cp=str(root)+':'+(root/'classpath.txt').read_text()
with (root/'collector.log').open('xb') as output:
    child=subprocess.Popen([java,'-cp',cp,'ObservePlanned',str(root),str(evidence)],
        cwd='/Users/mac/IdeaProjects/NoLate/NoLate_BE',stdout=output,stderr=subprocess.DEVNULL)
    stopped=False
    try:
        while child.poll() is None:
            if not stopped and provider_failed():
                stop_owned_parent('REAL_RUN_PROVIDER_ERROR');stopped=True
            time.sleep(1)
        report=json.loads((root/'collection-report.json').read_text())
        if not stopped and report.get('providerFailure'):
            stop_owned_parent('POSITION_PROVIDER_ERROR')
        print(json.dumps({'root':str(root),'collectorExit':child.returncode,'completedRequests':report['completedRequests'],
            'physicalHttpCalls':report['physicalHttpCalls'],'failureCode':report['failureCode']}),flush=True)
    finally:
        if child.poll() is None:
            child.terminate();child.wait(timeout=10)
