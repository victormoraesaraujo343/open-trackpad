//! The shortcut recorder: one window, one chord, then gone.
//!
//! # Why this is a separate program
//!
//! The daemon injects input and carries one dependency. This draws a window and
//! carries sixty-odd. Keeping them apart is what makes that acceptable — see
//! the note in `Cargo.toml`, which is where somebody counting dependencies will
//! look first.
//!
//! # Why it must always be escapable
//!
//! While it is open it asks the desktop to stop taking keyboard shortcuts for
//! itself, so that a combination like `super+shift+s` reaches this window rather
//! than firing the screenshot tool. That is the only way to record such a
//! chord, and it is also a loaded gun: a window that inhibits shortcuts and then
//! hangs leaves somebody on a desktop where their shortcuts have quietly
//! stopped working, with nothing obvious to blame.
//!
//! So there are three ways out and all of them are automatic:
//!
//! - Escape closes it, from any state;
//! - losing focus closes it, and it never fights to get focus back;
//! - it closes itself after `IDLE_TIMEOUT` with nothing pressed.
//!
//! Nothing about recording a shortcut is slow. A recorder nobody is using has
//! no business existing.

use std::cell::RefCell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use gtk4::gdk;
use gtk4::glib;
use gtk4::prelude::*;
use gtk4::{
    Align, Application, ApplicationWindow, Box as GtkBox, Button, CssProvider, Entry,
    EventControllerKey, Label, Orientation,
};

use opentrackpadd::keys::{name_of, Chord, ChordError, KeyCode};
use opentrackpadd::shortcuts::Shortcuts;

/// How long the window may sit with nothing pressed before it closes itself.
///
/// Long enough to look away and think, short enough that a forgotten window
/// cannot hold the desktop's own shortcuts hostage.
const IDLE_TIMEOUT: Duration = Duration::from_secs(30);

/// Everything the two states share, so the handlers can reach it.
struct Recorder {
    chord: RefCell<Option<Chord>>,
    /// The hardware codes currently held down.
    ///
    /// A chord is finished when this empties, not when its first key lands.
    /// Focus moving on the first key is what made `ctrl+shift+t` impossible:
    /// `ctrl` was captured, the name field took the caret, and `shift` and `t`
    /// were typed into it. A set rather than a count because key repeat sends
    /// press after press with no release in between.
    held: RefCell<std::collections::HashSet<u32>>,
    last_activity: RefCell<Instant>,
}

fn main() -> glib::ExitCode {
    let application = Application::builder()
        .application_id("org.opentrackpad.Recorder")
        .build();
    application.connect_activate(build);
    // No command-line handling: this program takes no arguments, which is also
    // what makes it safe for the daemon to spawn on a client's request.
    application.run_with_args::<&str>(&[])
}

fn build(application: &Application) {
    load_styles();

    let window = ApplicationWindow::builder()
        .application(application)
        .decorated(false)
        .fullscreened(true)
        .build();
    window.add_css_class("scrim");
    // The scrim is this window's own background rather than a layer beneath
    // somebody else's. A real overlay would need wlr-layer-shell, which GNOME
    // does not implement; a fullscreen transparent window looks identical and
    // works on every desktop. If the compositor will not composite alpha, the
    // background falls back to solid dark rather than rendering half-broken.
    if !gdk::Display::default().is_some_and(|display| display.is_composited()) {
        window.add_css_class("opaque");
    }

    let state = Rc::new(Recorder {
        chord: RefCell::new(None),
        held: RefCell::new(std::collections::HashSet::new()),
        last_activity: RefCell::new(Instant::now()),
    });

    let panel = GtkBox::new(Orientation::Vertical, 0);
    panel.add_css_class("panel");
    panel.set_halign(Align::Center);
    panel.set_valign(Align::Center);
    panel.set_size_request(460, -1);

    panel.append(&heading());

    let capture = GtkBox::new(Orientation::Vertical, 0);
    capture.add_css_class("capture");
    // The capture box holds the keys from the start, and says so.
    capture.add_css_class("active");
    capture.set_size_request(-1, 96);
    capture.set_valign(Align::Center);
    let waiting = waiting_prompt();
    capture.append(&waiting);
    panel.append(&capture);

    let hint = Label::new(Some(HINT));
    hint.add_css_class("hint");
    hint.set_halign(Align::Start);
    panel.append(&hint);

    let name = Entry::builder()
        .placeholder_text("Name this shortcut")
        .sensitive(false)
        .build();
    name.add_css_class("name");
    panel.append(&name);

    let save = Button::with_label("Save");
    save.add_css_class("save");
    save.set_sensitive(false);
    panel.append(&footer(&save));

    window.set_child(Some(&panel));

    watch_keys(&window, &state, &capture, &hint, &name, &save);
    record_again_on_click(&window, &state, &capture, &save);
    follow_the_active_box(&name, &capture);
    save_on_click(&window, &state, &name, &save);
    close_when_unfocused(&window);
    close_when_idle(&window, &state);

    window.present();
    // Asked for once the window exists: it is a request about this surface, and
    // there is no surface before it is presented.
    inhibit_desktop_shortcuts(&window);
}

fn heading() -> GtkBox {
    let row = GtkBox::new(Orientation::Horizontal, 0);
    row.add_css_class("heading");
    let title = Label::new(Some("New shortcut"));
    title.add_css_class("title");
    title.set_halign(Align::Start);
    title.set_hexpand(true);
    row.append(&title);
    row
}

fn waiting_prompt() -> GtkBox {
    let column = GtkBox::new(Orientation::Vertical, 0);
    column.set_valign(Align::Center);

    let row = GtkBox::new(Orientation::Horizontal, 10);
    row.set_halign(Align::Center);
    let dot = Label::new(Some("●"));
    dot.add_css_class("dot");
    row.append(&dot);
    let prompt = Label::new(Some("Press the combination now"));
    prompt.add_css_class("prompt");
    row.append(&prompt);
    column.append(&row);

    let where_ = Label::new(Some("On this computer's keyboard"));
    where_.add_css_class("where");
    where_.set_halign(Align::Center);
    column.append(&where_);
    column
}

fn footer(save: &Button) -> GtkBox {
    let row = GtkBox::new(Orientation::Horizontal, 12);
    row.add_css_class("footer");

    let kept = Label::new(Some(
        "Kept on this computer. Shows up on your phone right away.",
    ));
    kept.add_css_class("kept");
    kept.set_halign(Align::Start);
    kept.set_hexpand(true);
    kept.set_wrap(true);
    row.append(&kept);

    // Plain text, not a button. There is one button on this window.
    let cancel = Label::new(Some("Esc to cancel"));
    cancel.add_css_class("cancel");
    row.append(&cancel);
    row.append(save);
    row
}

/// Turns what GTK saw into the keys the daemon speaks about.
///
/// GTK reports a hardware keycode, which on Linux is the evdev code plus eight —
/// an X11 convention every layer below still carries. Going through the code
/// rather than the symbol is deliberate: the symbol depends on the layout, so on
/// a French keyboard `ctrl+a` by symbol is a different physical key from the one
/// that was pressed, and the shortcut would fire somewhere else.
fn keys_from(state: gdk::ModifierType, keycode: u32) -> Option<Vec<KeyCode>> {
    let mut keys = Vec::new();
    if state.contains(gdk::ModifierType::CONTROL_MASK) {
        keys.push(KeyCode::KEY_LEFTCTRL);
    }
    if state.contains(gdk::ModifierType::SHIFT_MASK) {
        keys.push(KeyCode::KEY_LEFTSHIFT);
    }
    if state.contains(gdk::ModifierType::ALT_MASK) {
        keys.push(KeyCode::KEY_LEFTALT);
    }
    if state.contains(gdk::ModifierType::SUPER_MASK) {
        keys.push(KeyCode::KEY_LEFTMETA);
    }
    keys.push(evdev_from_hardware(keycode)?);
    Some(keys)
}

/// An X11 hardware keycode is the evdev code plus eight — a convention every
/// layer below still carries.
fn evdev_from_hardware(keycode: u32) -> Option<KeyCode> {
    let code = keycode.checked_sub(8)?;
    u16::try_from(code).ok().map(KeyCode)
}

/// What the line under the box says when nothing has gone wrong.
const HINT: &str = "A combination, or a single key like Print — both work.";

/// Why a key press could not become a shortcut, in words for the person who
/// pressed it.
///
/// Every one of these used to be silence: the box simply did not change, which
/// looks like a broken recorder rather than a refused key. That is the failure
/// this project refuses everywhere else, and it is likeliest on a keyboard that
/// is not the one this vocabulary was written for.
fn refusal(error: &ChordError) -> String {
    match error {
        ChordError::Unknown(_) => {
            "That key has no name here yet, so it cannot be recorded.".to_owned()
        }
        ChordError::MagicSysRq => {
            "Alt with Print is the system's own emergency key, so it is not available.".to_owned()
        }
        ChordError::TooManyKeys => {
            "That is more keys than one shortcut can hold. Try three or four.".to_owned()
        }
        ChordError::Empty | ChordError::Repeated(_) => {
            "That combination cannot be recorded.".to_owned()
        }
    }
}

fn watch_keys(
    window: &ApplicationWindow,
    state: &Rc<Recorder>,
    capture: &GtkBox,
    hint: &Label,
    name: &Entry,
    save: &Button,
) {
    let controller = EventControllerKey::new();
    // Before the focused widget, not after it. Once the name field has the
    // caret it would otherwise swallow everything — including Escape, which is
    // the one guaranteed way out and is most wanted exactly when somebody has
    // just recorded the wrong thing.
    controller.set_propagation_phase(gtk4::PropagationPhase::Capture);

    let pressed_state = Rc::clone(state);
    let pressed_window = window.clone();
    let pressed_capture = capture.clone();
    let pressed_hint = hint.clone();
    let pressed_name = name.clone();

    controller.connect_key_pressed(move |_, key, keycode, modifiers| {
        *pressed_state.last_activity.borrow_mut() = Instant::now();

        // Escape on its own leaves, from any state and whatever has focus.
        // With a modifier it is a chord like any other, so `ctrl+escape` can
        // still be recorded — only the bare key is spoken for.
        if key == gdk::Key::Escape && modifiers.is_empty() {
            pressed_window.close();
            return glib::Propagation::Stop;
        }

        // Keys are typed only when somebody has clicked into the name field.
        // Nothing focuses it for them: after a chord lands the keys keep being
        // captured, so getting one wrong is corrected by pressing the right one
        // rather than by reaching for the mouse. On a window whose whole point
        // is that the keys are here, that trip is the thing to avoid.
        if pressed_name.has_focus() {
            return glib::Propagation::Proceed;
        }

        pressed_state.held.borrow_mut().insert(keycode);

        let Some(keys) = keys_from(modifiers, keycode) else {
            pressed_hint.set_text("That key has no name here yet, so it cannot be recorded.");
            pressed_hint.add_css_class("refused");
            return glib::Propagation::Stop;
        };
        // The same constructor the daemon uses, with the same refusals: a key
        // with no name here, the chord ceiling, and the alt-with-print pair are
        // all turned down at this line rather than after being saved.
        let chord = match Chord::from_keys(&keys) {
            Ok(chord) => chord,
            Err(error) => {
                // Said out loud. A key that does nothing and explains nothing
                // is indistinguishable from a recorder that has stopped
                // working.
                pressed_hint.set_text(&refusal(&error));
                pressed_hint.add_css_class("refused");
                return glib::Propagation::Stop;
            }
        };

        pressed_hint.set_text(HINT);
        pressed_hint.remove_css_class("refused");
        // Every press replaces the last, so a chord grows as fingers land:
        // ctrl, then ctrl+shift, then ctrl+shift+t. What is showing when the
        // last finger lifts is what gets saved.
        show_captured(&pressed_capture, &chord);
        *pressed_state.chord.borrow_mut() = Some(chord);
        glib::Propagation::Stop
    });

    let released_state = Rc::clone(state);
    let released_name = name.clone();
    let released_save = save.clone();
    controller.connect_key_released(move |_, _, keycode, _| {
        released_state.held.borrow_mut().remove(&keycode);
        if !released_state.held.borrow().is_empty() {
            return;
        }
        // Every finger is off and there is something to save.
        if let Some(chord) = released_state.chord.borrow().as_ref() {
            // Woken so it can be clicked, and deliberately not focused.
            released_name.set_sensitive(true);
            released_save.set_sensitive(true);
            // The suggestion is a placeholder rather than text, so clicking to
            // type starts clean instead of needing it deleted — and saving
            // without touching it lands the same words.
            released_name.set_placeholder_text(Some(&spelled_chord(chord)));
            // A pencil rather than a caret: editable without pretending to
            // already have the keys.
            released_name.set_secondary_icon_name(Some("document-edit-symbolic"));
        }
    });

    window.add_controller(controller);
}

/// Moves the lime border to whichever box is taking keys.
///
/// Exactly one of the two carries it, and it moves only when somebody clicks.
/// Nothing here changes what a key press means without saying so first — which
/// is the defect this replaces: the caret moved on its own, twice, and both
/// times there was no way to tell it had.
fn follow_the_active_box(name: &Entry, capture: &GtkBox) {
    let capture = capture.clone();
    name.connect_has_focus_notify(move |name| {
        if name.has_focus() {
            name.add_css_class("active");
            capture.remove_css_class("active");
        } else {
            name.remove_css_class("active");
            capture.add_css_class("active");
        }
    });
}

/// Clicking the capture box throws the chord away and listens again.
///
/// It is the only way back, because once a chord is caught the keys belong to
/// the name field. The line under the box says so.
fn record_again_on_click(
    window: &ApplicationWindow,
    state: &Rc<Recorder>,
    capture: &GtkBox,
    save: &Button,
) {
    let gesture = gtk4::GestureClick::new();
    let state = Rc::clone(state);
    let window = window.clone();
    let box_for_handler = capture.clone();
    let save = save.clone();

    gesture.connect_pressed(move |_, _, _, _| {
        *state.last_activity.borrow_mut() = Instant::now();
        *state.chord.borrow_mut() = None;
        state.held.borrow_mut().clear();

        // Nothing to save until a new chord lands: the old one is gone and
        // saving it would be saving something no longer on screen.
        save.set_sensitive(false);
        show_waiting(&box_for_handler);
        box_for_handler.add_css_class("active");
        // The caret has to leave the name field or the next key press would be
        // typed rather than captured.
        gtk4::prelude::GtkWindowExt::set_focus(&window, None::<&gtk4::Widget>);
    });
    capture.add_controller(gesture);
}

/// Puts the box back to waiting for a combination.
fn show_waiting(capture: &GtkBox) {
    while let Some(child) = capture.first_child() {
        capture.remove(&child);
    }
    capture.append(&waiting_prompt());
}

/// Replaces the prompt with the chord, drawn as key caps.
fn show_captured(capture: &GtkBox, chord: &Chord) {
    while let Some(child) = capture.first_child() {
        capture.remove(&child);
    }

    let row = GtkBox::new(Orientation::Horizontal, 8);
    row.set_halign(Align::Center);
    row.set_valign(Align::Center);
    for (position, key) in chord.keys().iter().enumerate() {
        if position > 0 {
            let plus = Label::new(Some("+"));
            plus.add_css_class("plus");
            row.append(&plus);
        }
        let cap = Label::new(Some(&cap_text(name_of(*key).unwrap_or("?"))));
        cap.add_css_class("cap");
        row.append(&cap);
    }
    capture.append(&row);

    let again = Label::new(Some(
        "Press another combination to replace it. Click the name to type instead.",
    ));
    again.add_css_class("where");
    again.set_halign(Align::Center);
    capture.append(&again);
}

/// The chord as somebody would write it: `Ctrl+Shift+T`.
///
/// Used as the name when nobody typed one. The wire spelling — `ctrl+shift+t` —
/// is for the protocol; this is for a person reading a list.
fn spelled_chord(chord: &Chord) -> String {
    chord
        .keys()
        .iter()
        .map(|key| cap_text(name_of(*key).unwrap_or("?")))
        .collect::<Vec<_>>()
        .join("+")
}

/// How a key name is drawn on a cap.
///
/// The names are the protocol's, which are lowercase and unspaced because they
/// travel on a wire. Nobody reads "pageup" on a key.
fn cap_text(name: &str) -> String {
    let spelled = match name {
        "pageup" => "Page Up",
        "pagedown" => "Page Down",
        "capslock" => "Caps Lock",
        "scrolllock" => "Scroll Lock",
        "numlock" => "Num Lock",
        "playpause" => "Play/Pause",
        "nexttrack" => "Next",
        "previoustrack" => "Previous",
        "volumeup" => "Volume Up",
        "volumedown" => "Volume Down",
        "brightnessup" => "Brightness Up",
        "brightnessdown" => "Brightness Down",
        "micmute" => "Mic Mute",
        "leftbracket" => "[",
        "rightbracket" => "]",
        "apostrophe" => "'",
        "semicolon" => ";",
        "backslash" => "\\",
        "grave" => "`",
        "comma" => ",",
        "period" => ".",
        "slash" => "/",
        "minus" => "-",
        "equal" => "=",
        other => return capitalise(other),
    };
    spelled.to_owned()
}

fn capitalise(name: &str) -> String {
    let mut characters = name.chars();
    match characters.next() {
        Some(first) => first.to_uppercase().collect::<String>() + characters.as_str(),
        None => String::new(),
    }
}

fn save_on_click(window: &ApplicationWindow, state: &Rc<Recorder>, name: &Entry, save: &Button) {
    let state = Rc::clone(state);
    let window = window.clone();
    let name = name.clone();
    save.connect_clicked(move |_| {
        let Some(chord) = state.chord.borrow().clone() else {
            return;
        };
        let typed = name.text();
        let typed = if typed.trim().is_empty() {
            // Naming is optional, because nothing focuses the field for them —
            // so somebody will record a chord and press Save. Refusing that,
            // over a field nobody said was required, would be the worst of the
            // options available. The chord is what they would have typed
            // anyway, it is never blank, and it is honest.
            spelled_chord(&chord)
        } else {
            typed.to_string()
        };

        // Read, add, write, leave. The daemon notices the file changed within a
        // couple of seconds; there is nothing to tell it and nothing to wait
        // for.
        let mut shortcuts = Shortcuts::open();
        match shortcuts.record(&typed, chord.keys()) {
            Ok(_) => window.close(),
            // Nothing here can be usefully retried from a window with one
            // button on it: the list is full, or the name is impossible. Say so
            // where it happened and leave the window open so the name can be
            // changed.
            Err(error) => eprintln!("could not save the shortcut: {error}"),
        }
    });
}

fn close_when_unfocused(window: &ApplicationWindow) {
    window.connect_is_active_notify(|window| {
        if !window.is_active() {
            // Never fight for focus back. A window that inhibits the desktop's
            // shortcuts and insists on staying in front is the shape of thing
            // people have to log out to escape.
            window.close();
        }
    });
}

fn close_when_idle(window: &ApplicationWindow, state: &Rc<Recorder>) {
    let state = Rc::clone(state);
    let window = window.clone();
    glib::timeout_add_seconds_local(1, move || {
        if state.last_activity.borrow().elapsed() >= IDLE_TIMEOUT {
            window.close();
            return glib::ControlFlow::Break;
        }
        glib::ControlFlow::Continue
    });
}

/// Asks the desktop to stop taking keyboard shortcuts while this window is up.
///
/// Without it a chord like `super+shift+s` never arrives: the compositor acts on
/// it first and the recorder sees nothing, which looks like a broken recorder
/// rather than a working desktop. GDK maps this onto the Wayland
/// keyboard-shortcuts-inhibit protocol and onto a keyboard grab on X11, so one
/// call covers both.
///
/// Failing is survivable and deliberately quiet: most chords still record, and
/// the ones the desktop keeps for itself simply never arrive. Refusing to open
/// over it would be worse than opening without it.
fn inhibit_desktop_shortcuts(window: &ApplicationWindow) {
    if let Some(toplevel) = window.surface().and_downcast::<gdk::Toplevel>() {
        toplevel.inhibit_system_shortcuts(None::<&gdk::ButtonEvent>);
    }
}

fn load_styles() {
    let provider = CssProvider::new();
    provider.load_from_data(include_str!("recorder.css"));
    if let Some(display) = gdk::Display::default() {
        gtk4::style_context_add_provider_for_display(
            &display,
            &provider,
            gtk4::STYLE_PROVIDER_PRIORITY_APPLICATION,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_hardware_keycode_is_an_evdev_code_plus_eight() {
        // 38 is `a` on every Linux keyboard: evdev 30, X11 38.
        assert_eq!(evdev_from_hardware(38), Some(KeyCode(30)));
        assert_eq!(evdev_from_hardware(9), Some(KeyCode(1)));
        // Nothing below the offset is a key.
        assert_eq!(evdev_from_hardware(0), None);
        assert_eq!(evdev_from_hardware(7), None);
    }

    #[test]
    fn the_modifier_codes_are_the_ones_the_daemon_names() {
        assert_eq!(name_of(KeyCode::KEY_LEFTCTRL), Some("ctrl"));
        assert_eq!(name_of(KeyCode::KEY_LEFTSHIFT), Some("shift"));
        assert_eq!(name_of(KeyCode::KEY_LEFTALT), Some("alt"));
        assert_eq!(name_of(KeyCode::KEY_LEFTMETA), Some("super"));
    }

    #[test]
    fn modifiers_come_before_the_key_they_modify() {
        let pressed = keys_from(
            gdk::ModifierType::CONTROL_MASK | gdk::ModifierType::SHIFT_MASK,
            // `t` is evdev 20, so X11 reports 28.
            20 + 8,
        )
        .expect("a chord");
        let chord = Chord::from_keys(&pressed).expect("valid");
        assert_eq!(chord.to_string(), "ctrl+shift+t");
    }

    #[test]
    fn a_lone_modifier_records() {
        // Pressing Ctrl reports the Ctrl key with no modifier state yet.
        let pressed = keys_from(gdk::ModifierType::empty(), 29 + 8).expect("a chord");
        assert_eq!(Chord::from_keys(&pressed).unwrap().to_string(), "ctrl");
    }

    #[test]
    fn the_recorder_cannot_save_what_the_daemon_would_refuse() {
        // alt+print is the kernel's magic SysRq sequence. It is turned down
        // here by the same function that turns it down on the socket, rather
        // than being saved and then never firing.
        let pressed = keys_from(gdk::ModifierType::ALT_MASK, 99 + 8).expect("keys");
        assert!(Chord::from_keys(&pressed).is_err());
    }

    #[test]
    fn key_caps_are_spelled_for_reading_rather_than_for_the_wire() {
        assert_eq!(cap_text("ctrl"), "Ctrl");
        assert_eq!(cap_text("t"), "T");
        assert_eq!(cap_text("f11"), "F11");
        assert_eq!(cap_text("print"), "Print");
        assert_eq!(cap_text("pageup"), "Page Up");
        assert_eq!(cap_text("leftbracket"), "[");
    }

    #[test]
    fn a_refused_key_is_explained_rather_than_ignored() {
        // Every one of these used to be silence, which is indistinguishable
        // from a recorder that has stopped working. Most likely to happen on a
        // keyboard that is not the one this vocabulary was written for.
        for error in [
            ChordError::Unknown("KEY_RO".to_owned()),
            ChordError::MagicSysRq,
            ChordError::TooManyKeys,
            ChordError::Empty,
            ChordError::Repeated("ctrl".to_owned()),
        ] {
            let said = refusal(&error);
            assert!(!said.is_empty(), "{error:?} was refused in silence");
            assert!(said.ends_with('.'), "{said:?} is not a sentence");
            // The person pressed a key; they should not be shown the name of a
            // kernel constant for their trouble.
            assert!(!said.contains("KEY_"), "{said:?} leaks an internal name");
        }
    }

    #[test]
    fn the_hint_says_both_shapes_are_allowed() {
        // A single key is a shortcut on its own, and somebody who does not know
        // that will try a combination and give up.
        assert!(HINT.contains("single key"));
    }

    #[test]
    fn a_chord_saved_without_a_name_reads_like_somebody_wrote_it() {
        // Naming is optional, so this is what lands in the list. The wire
        // spelling is for the protocol; a person reading a list gets this.
        let chord = Chord::parse("ctrl+shift+t").unwrap();
        assert_eq!(spelled_chord(&chord), "Ctrl+Shift+T");
        assert_eq!(chord.to_string(), "ctrl+shift+t");

        assert_eq!(spelled_chord(&Chord::parse("print").unwrap()), "Print");
        assert_eq!(spelled_chord(&Chord::parse("super").unwrap()), "Super");
        assert_eq!(
            spelled_chord(&Chord::parse("ctrl+pageup").unwrap()),
            "Ctrl+Page Up"
        );
    }

    #[test]
    fn a_name_from_a_chord_is_never_blank() {
        // The whole point of the fallback: Save can never be pressed on a
        // shortcut that then has nothing to call it.
        for text in ["ctrl+c", "f11", "super", "playpause", "leftbracket"] {
            let spelled = spelled_chord(&Chord::parse(text).unwrap());
            assert!(!spelled.trim().is_empty(), "{text} spelled to nothing");
        }
    }
}
