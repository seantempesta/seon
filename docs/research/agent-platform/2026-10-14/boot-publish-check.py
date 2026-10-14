"""Run one check and retain readiness before its owned JVM is removed."""
import glob
import pathlib
import re
import socket
import subprocess
import sys
import time

output = pathlib.Path(sys.argv[1])
with output.with_suffix('.log').open('w') as log:
    process = subprocess.Popen(['bin/seon', 'check', *sys.argv[2:]], stdout=log, stderr=subprocess.STDOUT)
    captured = set()
    while process.poll() is None:
        for path in glob.glob('data/check/slot-*/data/clusters/default/prepl.edn'):
            try:
                advertisement = pathlib.Path(path).read_text()
                pid = int(re.search(r':(?:seon.boot/)?pid (\d+)', advertisement)[1])
                parent = int(subprocess.check_output(['ps', '-o', 'ppid=', '-p', str(pid)], text=True).strip())
                while parent > 1 and parent != process.pid:
                    parent = int(subprocess.check_output(['ps', '-o', 'ppid=', '-p', str(parent)], text=True).strip())
                if parent != process.pid or pid in captured:
                    continue
                captured.add(pid)
                port = int(re.search(r':(?:seon.boot/)?prepl-port (\d+)', advertisement)[1])
                form = '''(loop [remaining 1200] (let [instance (get @seon.operator.runtime/running-instances "default")] (if (or (:seon.boot/ready-ms instance) (zero? remaining)) {:readiness (select-keys (seon.cluster.boot/readiness instance) [:seon.boot/layers :seon.boot/ready-ms]) :source-directory (seon.fs/source-directory) :profile (seon.profile/summary (seon.profile/cells) 12)} (do (Thread/sleep 100) (recur (dec remaining))))))\n'''
                with socket.create_connection(('127.0.0.1', port), timeout=125) as connection:
                    connection.sendall(form.encode())
                    response = connection.makefile().readline()
                    output.with_name(output.name + '-' + str(pid)).with_suffix('.edn').write_text(response)
            except (OSError, ValueError, TypeError, subprocess.CalledProcessError) as error:
                output.with_suffix('.errors').open('a').write(str(error) + '\n')
        time.sleep(.1)
    sys.exit(process.returncode)
