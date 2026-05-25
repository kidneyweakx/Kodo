# NOTICE

This project (`mi-band-9-active`) is a **derivative work** of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), licensed under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**.

## Upstream attribution

Portions of the Bluetooth pairing flow, Xiaomi V1/V2 BLE protocol parsers, sample/sleep stage parsers, notification icon packing, weather/calendar/music service payload schemas, and watchface install/transfer logic are **ported from Gadgetbridge's** `nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.*` and `nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.*` packages.

Every ported file retains its **original** `Copyright (C) … <author>` header. New files in this repo carry our own header that references this notice.

## What we kept / what we cut

We **only** ship support for `Xiaomi Smart Band 9 Active`. All other coordinators, services, providers, and protocol parsers from Gadgetbridge are excluded by design. See `FEATURES.md`.

## Trademarks

"Xiaomi", "Mi", "Mi Band", "Smart Band" are trademarks of Xiaomi Inc. This project is **not** affiliated with, endorsed by, or sponsored by Xiaomi.

## Your obligations as a user/distributor

Because this project inherits AGPL-3.0-or-later:

- You **must** ship the corresponding source when you distribute a binary.
- If you **modify** the program and let users interact with it over a network, you must offer those users the modified source.
- You may **not** add additional restrictions on top of the AGPL terms.

See `LICENSE` for the full text.
