"""Build protocol assets from the repository's single gateway-contract source."""
from pathlib import Path
import shutil

from setuptools import setup
from setuptools.command.build_py import build_py
from setuptools.command.sdist import sdist

PACKAGE_ROOT = Path(__file__).resolve().parent


def contract_source():
    # A source distribution carries its own copy; a repository build consumes
    # the canonical sibling tree. These files are never maintained twice.
    for source in (PACKAGE_ROOT / "gateway-contract", PACKAGE_ROOT.parent / "gateway-contract"):
        if (source / "core-dispatched-schemas.json").is_file():
            return source
    raise RuntimeError("gateway-contract assets are required to build OAI Core")


def copy_contract(destination):
    source = contract_source()
    for relative in (Path("core-dispatched-schemas.json"), *source.glob("schemas/*.json"), *source.glob("vectors/*.json")):
        relative = relative.relative_to(source) if relative.is_absolute() else relative
        target = Path(destination) / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / relative, target)


def copy_skills(destination):
    for source in (PACKAGE_ROOT / "skills", PACKAGE_ROOT.parent / "integrations" / "skills"):
        if (source / "oai-client" / "SKILL.md").is_file():
            target = Path(destination) / "skills"
            shutil.rmtree(target, ignore_errors=True)
            shutil.copytree(source, target)
            return
    raise RuntimeError("oai-client skill assets are required to build OAI Core")


class BuildPy(build_py):
    def run(self):
        super().run()
        copy_contract(Path(self.build_lib) / "oai_core" / "gateway" / "contracts")
        copy_skills(Path(self.build_lib) / "oai_core")


class SourceDistribution(sdist):
    def make_release_tree(self, base_dir, files):
        super().make_release_tree(base_dir, files)
        copy_contract(Path(base_dir) / "gateway-contract")
        copy_skills(Path(base_dir))


setup(cmdclass={"build_py": BuildPy, "sdist": SourceDistribution})
