//! Where pad events go: the real virtual device, or the terminal.

use std::io;

use crate::pad::{PadEvent, PadGeometry};

pub trait PadSink {
    fn emit(&mut self, events: &[PadEvent]) -> io::Result<()>;

    /// Makes the output match `geometry`, returning whether the underlying
    /// device had to be replaced to do it.
    fn configure(&mut self, geometry: PadGeometry) -> io::Result<bool>;
    /// Human-readable description for startup logging.
    fn describe(&mut self) -> String;
}

impl PadSink for crate::uinput::UinputTouchpad {
    fn emit(&mut self, events: &[PadEvent]) -> io::Result<()> {
        crate::uinput::UinputTouchpad::emit(self, events)
    }

    fn configure(&mut self, geometry: PadGeometry) -> io::Result<bool> {
        crate::uinput::UinputTouchpad::configure(self, geometry)
    }

    fn describe(&mut self) -> String {
        let geometry = crate::uinput::UinputTouchpad::geometry(self);
        let nodes = match self.device_nodes() {
            Ok(nodes) if !nodes.is_empty() => nodes
                .iter()
                .map(|path| path.display().to_string())
                .collect::<Vec<_>>()
                .join(", "),
            Ok(_) => "(no device node found)".to_owned(),
            Err(error) => format!("(device node lookup failed: {error})"),
        };
        let syspath = match self.syspath() {
            Ok(path) => path.display().to_string(),
            Err(error) => format!("(syspath lookup failed: {error})"),
        };
        format!(
            "virtual touchpad \"{}\", {}x{} mm, at {nodes} ({syspath})",
            crate::uinput::DEVICE_NAME,
            geometry.width_mm(),
            geometry.height_mm()
        )
    }
}

/// Prints events instead of injecting them. Useful when `/dev/uinput` is not
/// writable, and for seeing exactly what the state machine decided.
pub struct DebugSink;

impl PadSink for DebugSink {
    fn emit(&mut self, events: &[PadEvent]) -> io::Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        let rendered: Vec<String> = events.iter().map(|event| format!("{event:?}")).collect();
        println!("    {}", rendered.join(" "));
        Ok(())
    }

    fn configure(&mut self, geometry: PadGeometry) -> io::Result<bool> {
        println!(
            "    (would resize the pad to {}x{} mm)",
            geometry.width_mm(),
            geometry.height_mm()
        );
        Ok(false)
    }

    fn describe(&mut self) -> String {
        "no virtual device (dry run; events are printed only)".to_owned()
    }
}
