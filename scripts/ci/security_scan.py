#!/usr/bin/env python3
"""Run the existing security checks, distinguishing findings from incomplete scans."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path.cwd()
REPORTS = ROOT / 'security-reports'


def classify(kind, report):
    """Return whether findings meet the existing blocking threshold; reject incomplete JSON."""
    if kind == 'gitleaks':
        if not isinstance(report, list) or any(not isinstance(r, dict) or not r.get('RuleID') for r in report):
            raise ValueError('invalid gitleaks report')
        return bool(report), bool(report)
    if kind == 'semgrep':
        if not isinstance(report, dict) or not isinstance(report.get('results'), list) or not isinstance(report.get('errors'), list):
            raise ValueError('invalid semgrep report')
        if report['errors']:
            raise ValueError('semgrep did not complete all checks')
        severities = [r['extra']['severity'] for r in report['results']]
        if any(s not in ('ERROR', 'WARNING', 'INFO', 'INVENTORY', 'EXPERIMENT') for s in severities):
            raise ValueError('unknown semgrep severity')
        return bool(severities), 'ERROR' in severities
    if kind == 'npm':
        if not isinstance(report, dict) or report.get('error'):
            raise ValueError('npm audit failed')
        counts = report['metadata']['vulnerabilities']
        high, critical = counts['high'], counts['critical']
        if type(high) is not int or type(critical) is not int or min(high, critical) < 0:
            raise ValueError('invalid npm audit counts')
        if any(type(n) is not int or n < 0 for n in counts.values()):
            raise ValueError('invalid npm audit counts')
        return any(counts.values()), high + critical > 0
    if kind == 'osv':
        if not isinstance(report, dict) or not isinstance(report.get('results'), list):
            raise ValueError('invalid OSV report')
        if any(not isinstance(s, dict) or not isinstance(s.get('packages'), list) for s in report['results']):
            raise ValueError('invalid OSV source')
        packages = [p for source in report['results'] for p in source['packages']]
        if not packages or any(not isinstance(p, dict) or not isinstance(p.get('package'), dict)
                               or not p['package'].get('version')
                               or not isinstance(p.get('vulnerabilities', []), list) for p in packages):
            raise ValueError('OSV did not scan resolved dependencies')
        findings = any(p.get('vulnerabilities') for p in packages)
        # All Maven advisories require triage, including ones without a severity score.
        return findings, findings
    if not isinstance(report, dict) or not isinstance(report.get('dependencies'), list) or not isinstance(report.get('scanInfo'), dict):
        raise ValueError('invalid dependency-check report')
    if report['scanInfo'].get('analysisExceptions'):
        raise ValueError('dependency-check analysis was incomplete')
    scores = []
    for dependency in report['dependencies']:
        for vulnerability in dependency.get('vulnerabilities', []):
            values = [float(vulnerability[version]['baseScore']) for version in ('cvssv2', 'cvssv3', 'cvssv4')
                      if version in vulnerability and 'baseScore' in vulnerability[version]]
            if not values or any(not 0 <= score <= 10 for score in values):
                raise ValueError('missing or invalid vulnerability score')
            scores.append(max(values))
    return bool(scores), any(score >= 9 for score in scores)


def main():
    REPORTS.mkdir(exist_ok=True)
    # Never accept a previous run's report after a scanner dies before writing a new one.
    summary_file = REPORTS / 'summary.json'
    summary_file.unlink(missing_ok=True)
    summary = {}

    def scan(name, kind, command, cwd=ROOT, report_name=None):
        path = REPORTS / (report_name or name + '.json')
        path.unlink(missing_ok=True)
        print('Scanning: ' + name, flush=True)
        result = {'status': 'ERROR', 'report': str(path.relative_to(REPORTS))}
        with (REPORTS / (name + '.log')).open('w') as log:
            try:
                process = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, check=False, timeout=600)
                result['exit_code'] = process.returncode
                report = json.loads(path.read_text())
                findings, blocking = classify(kind, report)
                # gitleaks/semgrep use 1 for findings. Maven uses 0 because failBuildOnCVSS=11.
                allowed = {0, 1} if kind in ('gitleaks', 'semgrep', 'npm', 'osv') and findings else {0}
                if process.returncode not in allowed:
                    raise ValueError('scanner execution failed')
                result.update(status='FINDINGS' if findings else 'PASSED', blocking=blocking)
            except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
                result['reason'] = 'scanner failed or report missing, malformed or incomplete'
        summary[name] = result
        print(name + ': ' + result['status'], flush=True)

    def npm(name, checkout, production=False):
        npm_lock, pnpm_lock = checkout / 'package-lock.json', checkout / 'pnpm-lock.yaml'
        if npm_lock.is_file() == pnpm_lock.is_file():
            summary[name] = {'status': 'ERROR', 'reason': 'exactly one supported dependency lockfile is required'}
            return
        manager = 'pnpm' if pnpm_lock.is_file() else 'npm'
        # The audit needs only committed dependency versions, not an installed node_modules tree.
        report = REPORTS / (name + '.json')
        # npm writes JSON to stdout, whereas the other tools accept a report path.
        report.unlink(missing_ok=True)
        try:
            with report.open('w') as output, (REPORTS / (name + '.log')).open('w') as log:
                command = [manager, 'audit', '--registry=https://registry.npmjs.org', '--audit-level=high', '--json']
                if manager == 'npm':
                    command.extend(['--package-lock-only', '--ignore-scripts'])
                if production:
                    command.append('--prod' if manager == 'pnpm' else '--omit=dev')
                process = subprocess.run(command, cwd=checkout, stdout=output, stderr=log, check=False, timeout=180)
            findings, blocking = classify('npm', json.loads(report.read_text()))
            if process.returncode not in ({0, 1} if findings else {0}):
                raise ValueError('npm execution failed')
            summary[name] = {'status': 'FINDINGS' if findings else 'PASSED', 'blocking': blocking, 'exit_code': process.returncode, 'package_manager': manager}
        except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
            summary[name] = {'status': 'ERROR', 'reason': 'npm failed or audit report invalid'}
        print(name + ': ' + summary[name]['status'], flush=True)

    scan('gitleaks', 'gitleaks', ['gitleaks', 'git', '.', '--log-opts=--all', '--config', '.gitleaks.toml',
         '--redact=100', '--report-format', 'json', '--report-path', str(REPORTS / 'gitleaks.json')])
    # YAML is included: flow-style Unicode scalars use portable quoting in the source.
    semgrep = ['semgrep', 'scan', '--config', 'p/java', '--config', 'p/typescript', '--config', 'p/secrets',
               '--error', '--timeout', '30', '--exclude', 'security-reports', '--json']
    scan('semgrep', 'semgrep', [*semgrep, '--output', str(REPORTS / 'semgrep.json'), '.'])
    if (ROOT / 'pom.xml').is_file():
        # Resolve the actual Maven graph, rather than scanning only versions written in pom.xml.
        # OSV does not need an NVD key; dependency-check remains an additional check when configured.
        bom = REPORTS / 'bom.json'
        bom.unlink(missing_ok=True)
        try:
            with (REPORTS / 'maven-sbom.log').open('w') as log:
                subprocess.run(['mvn', '-B', 'org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom',
                                '-DincludeTestScope=false', '-DoutputFormat=json', '-DoutputName=bom',
                                '-DoutputDirectory=' + str(REPORTS)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
            document = json.loads(bom.read_text())
            if not isinstance(document, dict) or document.get('bomFormat') != 'CycloneDX' or not document.get('components'):
                raise ValueError('empty or invalid Maven SBOM')
            summary['maven-sbom'] = {'status': 'PASSED', 'components': len(document['components'])}
            scan('osv-maven', 'osv', ['osv-scanner', 'scan', 'source', '--sbom', str(bom),
                                    '--all-packages', '--format', 'json', '--output-file', str(REPORTS / 'osv-maven.json')])
        except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            summary['maven-sbom'] = {'status': 'ERROR', 'reason': 'Maven dependency resolution or SBOM generation failed'}
        if os.environ.get('NVD_API_KEY'):
            directory = REPORTS / 'dependency-check'
            directory.mkdir(exist_ok=True)
            scan('dependency-check', 'dependency-check', ['mvn', '-B', 'org.owasp:dependency-check-maven:check',
                 '-DnvdApiKey=' + os.environ['NVD_API_KEY'], '-DfailBuildOnCVSS=11', '-Dformat=JSON',
                 '-Dodc.outputDirectory=' + str(directory), '-DfailOnError=true'], report_name='dependency-check/dependency-check-report.json')
        else:
            summary['dependency-check'] = {'status': 'SKIPPED', 'reason': 'NVD_API_KEY not configured'}
    if (ROOT / 'package.json').is_file():
        npm('node-root', ROOT)
        npm('node-root-production', ROOT, production=True)
    frontend = Path(os.environ.get('FRONTEND_DIR', ROOT / '../smart-closet' if (ROOT / 'agent-runtime').is_dir() else ROOT / '.no-related-frontend')).resolve()
    if (frontend / 'package-lock.json').is_file() or (frontend / 'pnpm-lock.yaml').is_file():
        scan('semgrep-frontend', 'semgrep', ['semgrep', 'scan', '--config', 'p/typescript', '--config', 'p/secrets',
             '--error', '--json', '--output', str(REPORTS / 'semgrep-frontend.json'), str(frontend)])
        npm('npm-frontend', frontend)
        npm('npm-frontend-production', frontend, production=True)
    elif 'FRONTEND_DIR' in os.environ:
        summary['npm-frontend'] = {'status': 'ERROR', 'reason': 'explicit frontend checkout missing a supported lockfile'}
    else:
        skipped = {'status': 'SKIPPED', 'reason': 'frontend is scanned in its own CI; no sibling checkout'}
        summary['npm-frontend'] = skipped
        summary['semgrep-frontend'] = skipped
    if (ROOT / 'agent-runtime').is_dir():
        npm('npm-agent', ROOT / 'agent-runtime')
        npm('npm-agent-production', ROOT / 'agent-runtime', production=True)
    if not any((ROOT / name).exists() for name in ('pom.xml', 'package.json', 'agent-runtime')):
        summary['scope'] = {'status': 'ERROR', 'reason': 'no application dependency manifest found'}
    summary_file.write_text(json.dumps(summary, indent=2) + '\n')
    if any(result['status'] == 'ERROR' for result in summary.values()):
        print('Security scanning incomplete; see security-reports/summary.json', flush=True)
        return 2
    if os.environ.get('SECURITY_SCAN_ENFORCE', '1') != '0' and any(result.get('blocking') for result in summary.values()):
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
