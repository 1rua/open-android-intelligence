"""Ship the standard Hermes loader manifest with the optional wheel."""
from pathlib import Path

from setuptools import setup
from setuptools.command.build_py import build_py


class BuildPy(build_py):
    def run(self):
        super().run()
        target = Path(self.build_lib) / "oai_hermes_adapter" / "plugin.yaml"
        target.parent.mkdir(parents=True, exist_ok=True)
        self.copy_file("plugin.yaml", str(target))


setup(cmdclass={"build_py": BuildPy})
