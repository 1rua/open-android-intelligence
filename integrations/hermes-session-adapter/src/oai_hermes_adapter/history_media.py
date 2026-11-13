"""Hermes media locations and validation over Core's single storage library."""
from pathlib import Path

from oai_core.gateway.history_media import HistoryMedia as CoreHistoryMedia


class HistoryMedia(CoreHistoryMedia):
    @staticmethod
    def _host_home():
        from hermes_constants import get_hermes_home
        return Path(get_hermes_home()).resolve()

    def register(self, conversation_id, message_id, path, media_type=None):
        from gateway.platforms.base import validate_media_delivery_path
        self.account.validate_media_delivery_path = validate_media_delivery_path
        return super().register(conversation_id, message_id, path, media_type)
