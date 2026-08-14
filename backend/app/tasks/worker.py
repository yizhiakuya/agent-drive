"""Standalone task worker entry point: python -m app.tasks.worker."""
from __future__ import annotations

import asyncio
import signal

from ..core.container import Container


async def _main() -> None:
    container = Container()
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except (NotImplementedError, RuntimeError):
            pass
    await container.task_runner.start()
    try:
        await stop.wait()
    finally:
        await container.task_runner.stop()
        container.close()


def main() -> None:
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
