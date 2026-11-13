"""Compatibility access to an explicitly installed optional Hermes adapter.

Importing Core does not install or import the optional component. New host
plugins import oai_hermes_adapter directly.
"""

def register(ctx):
    from oai_hermes_adapter import register as native_register
    return native_register(ctx)


def __getattr__(name):
    if name in {"UnsupportedApprovalMixin", "create_hermes_bridge_app"}:
        from importlib import import_module
        return getattr(import_module("oai_hermes_adapter.bridge"), name)
    raise AttributeError(name)
