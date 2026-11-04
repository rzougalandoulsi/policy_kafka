"""Read-only Confluent Cloud RBAC optimization analyzer.

The package never talks to Confluent Cloud. It reads an exported inventory,
reasons about it offline, and writes reports. Nothing in here mutates
anything except files under the chosen output directory.
"""

__version__ = "0.1.0"
