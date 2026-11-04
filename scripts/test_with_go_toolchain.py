import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('with_go_toolchain.sh')


class GoToolchainEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dsh-go-toolchain-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = os.environ.copy()
        self.environment.pop('GOROOT', None)
        self.environment['PATH'] = str(self.root / 'empty-path')

    def make_go(self, directory, output):
        directory.mkdir(parents=True)
        executable = directory / 'go'
        executable.write_text('#!/bin/sh\nprintf "%s" "' + output + '"\n')
        executable.chmod(0o755)

    def run_command(self, *arguments):
        return subprocess.run(
            ['/bin/sh', str(SCRIPT), *arguments],
            env=self.environment, capture_output=True, text=True, check=False,
        )

    def test_respects_go_already_selected_in_path(self):
        selected = self.root / 'selected-bin'
        self.make_go(selected, 'selected-go')
        alternate = self.root / 'alternate-root' / 'bin'
        self.make_go(alternate, 'alternate-go')
        self.environment['PATH'] = str(selected)
        self.environment['GOROOT'] = str(alternate.parent)
        result = self.run_command('go', 'version')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'selected-go')

    def test_gui_path_discovers_goroot_with_spaces(self):
        installation = self.root / 'Go installation with spaces'
        self.make_go(installation / 'bin', 'goroot-go')
        self.environment['GOROOT'] = str(installation)
        result = self.run_command('go', 'version')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'goroot-go')

    def test_keeps_child_exit_status_and_literal_arguments(self):
        selected = self.root / 'bin'
        self.make_go(selected, 'go')
        self.environment['PATH'] = str(selected)
        literal = 'argument with spaces; literal $HOME'
        result = self.run_command('/bin/sh', '-c', 'printf "%s" "$1"; exit 23', 'probe', literal)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(result.stdout, literal)

    def test_missing_command_reports_usage(self):
        result = self.run_command()
        self.assertEqual(result.returncode, 64)
        self.assertIn('Usage:', result.stderr)


if __name__ == '__main__':
    unittest.main()
