# Linux host daemon

`opentrackpadd` currently implements the loopback TCP listener, protocol validation, frame ordering checks, bounds checks, and parser tests.

It intentionally does not request elevated privileges and does not write to `/dev/uinput` yet.

```bash
cargo test
cargo run
```

The optional first argument changes the listen address. Do not expose the prototype to a network interface; it has no authentication yet.
