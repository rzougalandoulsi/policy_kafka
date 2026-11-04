"""Exception types that the CLI turns into one-line messages."""


class AnalyzerError(Exception):
    """Base class for errors reported without a stack trace."""

    exit_code = 1


class InputError(AnalyzerError):
    """The inventory file is missing, unreadable, or structurally invalid."""

    exit_code = 1


class ConfigError(AnalyzerError):
    """The configuration file is missing, unreadable, or invalid."""

    exit_code = 1


class UsageError(AnalyzerError):
    """The command line was used incorrectly."""

    exit_code = 2
