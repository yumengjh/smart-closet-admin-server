import json
import os
from pathlib import Path
import subprocess
import tempfile
import shutil
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'security-scan.sh'


class SecurityScanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='security-scan-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'pom.xml').write_text('<project/>')
        (self.root / 'agent-runtime').mkdir()
        (self.root / 'agent-runtime/package-lock.json').write_text('{}')
        (self.root / '.gitleaks.toml').write_text('[extend]\nuseDefault=true\n')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # These executables model each scanner's CLI and JSON, not the production classifier.
        scanner = '''#!/usr/bin/env python3
import json,os,pathlib,sys
name=pathlib.Path(sys.argv[0]).name
p=json.loads(pathlib.Path(os.environ['SCAN_FIXTURE']).read_text())[name]
if name in ('npm','pnpm'):
 print(p.get('raw', json.dumps(p.get('report'))))
else:
 if name == 'mvn' and any('makeAggregateBom' in a for a in sys.argv):
  directory=next(x.split('=',1)[1] for x in sys.argv if x.startswith('-DoutputDirectory='))
  pathlib.Path(directory,'bom.json').write_text(json.dumps({'bomFormat':'CycloneDX','components':[{'name':'fixture'}]}))
  sys.exit(p.get('sbom_exit',0))
 if name == 'mvn':
  directory=next(x.split('=',1)[1] for x in sys.argv if x.startswith('-Dodc.outputDirectory='))
  if not p.get('missing'): pathlib.Path(directory,'dependency-check-report.json').write_text(p.get('raw',json.dumps(p.get('report'))))
 flag='--report-path' if name == 'gitleaks' else '--output-file' if name == 'osv-scanner' else '--output'
 if flag in sys.argv and not p.get('missing'):
  pathlib.Path(sys.argv[sys.argv.index(flag)+1]).write_text(p.get('raw',json.dumps(p.get('report'))))
sys.exit(p.get('exit',0))
'''
        for name in ('gitleaks', 'semgrep', 'npm', 'pnpm', 'mvn', 'osv-scanner'):
            path = self.bin / name
            path.write_text(scanner)
            path.chmod(0o755)
        self.fixture = {'mvn': {}, 'osv-scanner': {'report': {'results': [{'packages': [{'package': {'name': 'fixture', 'version': '1'}}]}]}}, 'gitleaks': {'report': []},
                        'semgrep': {'report': {'results': [], 'errors': []}},
                        'npm': {'report': {'metadata': {'vulnerabilities': {'high': 0, 'critical': 0}}}}}
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        SCAN_FIXTURE=str(self.root / 'fixture.json'))
        self.env.pop('NVD_API_KEY', None)
        self.env.pop('FRONTEND_DIR', None)
        self.env['SECURITY_SCAN_ENFORCE'] = '0'

    def scan(self):
        (self.root / 'fixture.json').write_text(json.dumps(self.fixture))
        return subprocess.run(['bash', str(SCRIPT)], cwd=self.root, env=self.env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_clean_scan_records_intentional_skips(self):
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout)
        summary = json.loads((self.root / 'security-reports/summary.json').read_text())
        self.assertEqual(summary['dependency-check']['status'], 'SKIPPED')
        self.assertEqual(summary['npm-agent']['status'], 'PASSED')

    def test_missing_scanner_fails_even_in_report_only_mode(self):
        (self.bin / 'semgrep').write_text('#!/bin/sh\nexit 127\n')
        self.assertNotEqual(self.scan().returncode, 0)

    def test_scan_error_with_empty_or_stale_report_is_not_a_pass(self):
        self.assertEqual(self.scan().returncode, 0)
        self.fixture['gitleaks'] = {'missing': True, 'exit': 2}
        self.assertNotEqual(self.scan().returncode, 0)

    def test_missing_malformed_and_wrong_shape_reports_fail(self):
        for profile in ({'missing': True}, {'raw': 'invalid'}, {'report': None}, {'report': {}}):
            with self.subTest(profile=profile):
                self.fixture['gitleaks'] = profile
                self.assertNotEqual(self.scan().returncode, 0)

    def test_findings_are_reported_and_gate_only_when_enforced(self):
        self.fixture['gitleaks'] = {'report': [{'RuleID': 'fake-secret'}], 'exit': 1}
        self.assertEqual(self.scan().returncode, 0)
        self.env['SECURITY_SCAN_ENFORCE'] = '1'
        self.assertNotEqual(self.scan().returncode, 0)

    def test_semgrep_error_is_incomplete_even_with_findings(self):
        self.fixture['semgrep'] = {'report': {'results': [{'extra': {'severity': 'ERROR'}}],
                                             'errors': [{'level': 'error'}]}, 'exit': 1}
        self.assertNotEqual(self.scan().returncode, 0)

    def test_semgrep_warning_is_not_a_high_severity_gate(self):
        self.fixture['semgrep'] = {'report': {'results': [{'extra': {'severity': 'WARNING'}}], 'errors': []}, 'exit': 1}
        self.env['SECURITY_SCAN_ENFORCE'] = '1'
        self.assertEqual(self.scan().returncode, 0)

    def test_npm_registry_error_and_missing_lock_fail(self):
        self.fixture['npm'] = {'report': {'error': {'code': 'ECONNRESET'}}, 'exit': 1}
        self.assertNotEqual(self.scan().returncode, 0)
        (self.root / 'agent-runtime/package-lock.json').unlink()
        self.assertNotEqual(self.scan().returncode, 0)

    def test_npm_high_findings_report_and_enforce(self):
        self.fixture['npm'] = {'report': {'metadata': {'vulnerabilities': {'high': 1, 'critical': 0}}}, 'exit': 1}
        self.assertEqual(self.scan().returncode, 0)
        self.env['SECURITY_SCAN_ENFORCE'] = '1'
        self.assertNotEqual(self.scan().returncode, 0)

    def test_dependency_check_report_and_execution_failures(self):
        self.env['NVD_API_KEY'] = 'fixture'
        report = {'scanInfo': {}, 'dependencies': [{'vulnerabilities': [{'cvssv3': {'baseScore': 9.8}}]}]}
        self.fixture['mvn'] = {'report': report}
        self.assertEqual(self.scan().returncode, 0)
        self.env['SECURITY_SCAN_ENFORCE'] = '1'
        self.assertNotEqual(self.scan().returncode, 0)
        self.env.pop('SECURITY_SCAN_ENFORCE')
        self.fixture['mvn']['exit'] = 1
        self.assertNotEqual(self.scan().returncode, 0, 'a valid report does not excuse a Maven failure')
        self.fixture['mvn'] = {'report': {'scanInfo': {'analysisExceptions': [{}]}, 'dependencies': []}}
        self.assertNotEqual(self.scan().returncode, 0)

    def test_maven_osv_findings_block_by_default(self):
        self.env.pop('SECURITY_SCAN_ENFORCE')
        self.fixture['osv-scanner'] = {'report': {'results': [{'packages': [
            {'package': {'name': 'fixture', 'version': '1'}, 'vulnerabilities': [{'id': 'GHSA-fixture'}]}]}]}, 'exit': 1}
        self.assertEqual(self.scan().returncode, 1)

    def test_maven_resolution_failure_and_empty_osv_report_fail(self):
        self.fixture['mvn']['sbom_exit'] = 1
        self.assertEqual(self.scan().returncode, 2)
        self.fixture['mvn'] = {}
        self.fixture['osv-scanner'] = {'report': {'results': []}}
        self.assertEqual(self.scan().returncode, 2)

    def test_pnpm_frontend_is_scanned_without_maven_or_agent(self):
        (self.root / 'pom.xml').unlink()
        shutil.rmtree(self.root / 'agent-runtime')
        (self.root / 'package.json').write_text('{}')
        (self.root / 'pnpm-lock.yaml').write_text('lockfileVersion: 9')
        self.fixture['pnpm'] = {'report': {'metadata': {'vulnerabilities': {'high': 0, 'critical': 0}}}}
        self.assertEqual(self.scan().returncode, 0)
        summary = json.loads((self.root / 'security-reports/summary.json').read_text())
        self.assertEqual(summary['node-root']['package_manager'], 'pnpm')
        self.assertNotIn('maven-sbom', summary)
        self.assertNotIn('npm-agent', summary)
        self.fixture['pnpm']['report']['metadata']['vulnerabilities']['high'] = 1
        self.fixture['pnpm']['exit'] = 1
        self.env.pop('SECURITY_SCAN_ENFORCE')
        self.assertEqual(self.scan().returncode, 1)

    def test_two_lockfiles_are_not_silently_treated_as_one(self):
        (self.root / 'package.json').write_text('{}')
        (self.root / 'package-lock.json').write_text('{}')
        (self.root / 'pnpm-lock.yaml').write_text('lockfileVersion: 9')
        self.assertEqual(self.scan().returncode, 2)

    def test_explicit_missing_frontend_is_an_error(self):
        self.env['FRONTEND_DIR'] = str(self.root / 'not-a-checkout')
        self.assertNotEqual(self.scan().returncode, 0)


if __name__ == '__main__':
    unittest.main()
