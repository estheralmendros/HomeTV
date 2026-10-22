import logging

from hometv.logging import configure_logging


def test_configure_logging_sets_level():
    configure_logging("debug")
    assert logging.getLogger("hometv").getEffectiveLevel() == logging.DEBUG
    configure_logging("warning")
    assert logging.getLogger("hometv").getEffectiveLevel() == logging.WARNING
