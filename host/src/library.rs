//! The two domains about shortcuts: what is recorded, and what is on offer.
//!
//! # Why they are two domains and not one
//!
//! `shortcuts` is what has been recorded and can be fired — the profile
//! editor's library. `import` is what this computer has that OpenTrackpad does
//! not: offered, reviewed, accepted or not.
//!
//! A candidate is not a shortcut with a flag on it. Folding them together would
//! leave `shortcuts` carrying entries that cannot be fired, which is precisely
//! how a button that does nothing gets built. Keeping them apart means every
//! entry in `shortcuts` is fireable and every entry in `import` is not, and
//! neither list has to be read with a caveat.
//!
//! # Why the offer can be asked for again
//!
//! Someone who binds a new desktop shortcut next month should be able to pick
//! it up. A one-shot offer at first connection makes the only chance to say yes
//! the moment somebody is least ready to decide. So the host offers whatever it
//! finds that is not already recorded, whenever asked; with nothing to offer the
//! domain is simply empty, the way audio is absent with no sound daemon.
//!
//! # Why it is threaded
//!
//! Reading a desktop's configuration means a file on KDE and five `gsettings`
//! calls on GNOME. That is tens of milliseconds, and the thread it must not
//! happen on is the one carrying touch frames.

use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Instant;

use crate::import::{self, Candidate};
use crate::panel::Sender;
use crate::protocol::{Capabilities, Domain, Outbound, Record, Refusal, Request, Verb};
use crate::shortcuts::{Origin, Shortcut, Shortcuts};
use crate::timing::TokenBucket;

const INBOX_DEPTH: usize = 32;

/// Renaming and deleting are typed by a person, one at a time; accepting is one
/// button. None of it is dragged, so the shortcut allowance is generous enough.
const REQUESTS_PER_SECOND: f64 = 50.0;
const REQUEST_BURST: f64 = 20.0;

/// Told when the shortcuts file changed, so the client hears about a shortcut
/// recorded while it is connected.
///
/// Held behind a lock the watcher shares, because the watcher outlives any one
/// session and there is at most one session at a time.
#[derive(Clone)]
pub struct Notifier {
    inbox: SyncSender<Wake>,
}

impl Notifier {
    pub fn changed(&self) {
        // A full inbox already says "look again".
        let _ = self.inbox.try_send(Wake::Changed);
    }
}

/// Where the daemon leaves a notifier for the watcher to find.
pub type Watched = Arc<Mutex<Option<Notifier>>>;

enum Wake {
    Request(Request),
    Changed,
    Stop,
}

pub struct Library {
    inbox: SyncSender<Wake>,
    rate: TokenBucket,
}

impl Library {
    pub fn start(out: Sender, shortcuts: Arc<RwLock<Shortcuts>>, granted: Capabilities) -> Self {
        let (inbox, wakes) = mpsc::sync_channel(INBOX_DEPTH);
        let worker = Worker {
            out,
            shortcuts,
            granted,
            shortcuts_generation: 0,
            import_generation: 0,
            offer: Vec::new(),
        };
        let _ = std::thread::Builder::new()
            .name("library".to_owned())
            .spawn(move || worker.run(&wakes));
        Self {
            inbox,
            rate: TokenBucket::new(Instant::now(), REQUEST_BURST, REQUESTS_PER_SECOND),
        }
    }

    pub fn notifier(&self) -> Notifier {
        Notifier {
            inbox: self.inbox.clone(),
        }
    }

    /// Hands a request over. Never blocks: this is the thread carrying touch.
    pub fn request(&mut self, request: Request, now: Instant) -> Option<Refusal> {
        if !self.rate.allow(now) {
            return Some(Refusal::TooFast);
        }
        match self.inbox.try_send(Wake::Request(request)) {
            Ok(()) => None,
            Err(_) => Some(Refusal::TooFast),
        }
    }
}

impl Drop for Library {
    fn drop(&mut self) {
        // Asked to stop and deliberately not waited for, for the same reason
        // the audio panel is not: reading a desktop's configuration can be
        // slow, and it must not be able to hold a session open.
        let _ = self.inbox.try_send(Wake::Stop);
    }
}

struct Worker {
    out: Sender,
    shortcuts: Arc<RwLock<Shortcuts>>,
    granted: Capabilities,
    shortcuts_generation: u64,
    import_generation: u64,
    /// The candidates last offered, with the numbers they were offered under.
    ///
    /// Those numbers are the host's and mean nothing outside the generation
    /// they were sent in, which is why accepting carries the generation.
    offer: Vec<(u32, Candidate)>,
}

impl Worker {
    fn run(mut self, wakes: &Receiver<Wake>) {
        self.send_shortcuts();
        self.send_offer();

        while let Ok(wake) = wakes.recv() {
            match wake {
                Wake::Stop => return,
                Wake::Changed => {
                    // Something wrote the file — the recorder, most likely.
                    // Both pictures move: a newly recorded shortcut joins the
                    // library and leaves the offer if it was in it.
                    self.send_shortcuts();
                    self.send_offer();
                }
                Wake::Request(request) => self.carry_out(request),
            }
        }
    }

    fn carry_out(&mut self, request: Request) {
        let sequence = request.sequence;
        match (request.domain, request.verb) {
            (Domain::Shortcuts, Verb::Refresh) => self.send_shortcuts(),
            (Domain::Import, Verb::Refresh) => self.send_offer(),

            (Domain::Shortcuts, Verb::Rename { id, name }) => {
                match self.editable(id) {
                    Err(reason) => self.refuse(sequence, reason),
                    Ok(()) => {
                        let outcome = self
                            .shortcuts
                            .write()
                            .expect("the list is never poisoned")
                            .rename(id, &name);
                        match outcome {
                            // The snapshot that follows is the acknowledgement,
                            // and it carries what the name actually became
                            // rather than what was asked for.
                            Ok(()) => self.send_shortcuts(),
                            Err(_) => self.refuse(sequence, Refusal::BackendFailed),
                        }
                    }
                }
            }
            (Domain::Shortcuts, Verb::Delete { id }) => match self.editable(id) {
                Err(reason) => self.refuse(sequence, reason),
                Ok(()) => {
                    let outcome = self
                        .shortcuts
                        .write()
                        .expect("the list is never poisoned")
                        .remove(id);
                    match outcome {
                        Ok(()) => {
                            // Deleting something that is on a rail leaves a
                            // hole. The rail keeps its five places and the
                            // client fills the gap; what matters here is that
                            // it is told, rather than finding out when the
                            // button stops working.
                            self.send_shortcuts();
                            self.send_offer();
                        }
                        Err(_) => self.refuse(sequence, Refusal::UnknownId),
                    }
                }
            },

            (Domain::Import, Verb::Accept { generation, ids }) => {
                self.accept(sequence, generation, &ids)
            }

            // The parser will not build one domain's verb for another, so
            // anything here is a bug rather than a client.
            (domain, verb) => {
                eprintln!("the library was given {verb:?} for {domain}");
                self.refuse(sequence, Refusal::WrongKind);
            }
        }
    }

    /// Whether this is a shortcut the person may change.
    ///
    /// A shipped convention and something read out of their desktop
    /// configuration are not ours to edit: a rename would reappear the next
    /// time the file is read, and a deletion would come back on the next
    /// import. Refusing says so; pretending would be worse.
    fn editable(&self, id: u32) -> Result<(), Refusal> {
        let held = self.shortcuts.read().expect("the list is never poisoned");
        match held.find(id) {
            None => Err(Refusal::UnknownId),
            Some(found) if !found.origin.is_editable() => Err(Refusal::WrongKind),
            Some(_) => Ok(()),
        }
    }

    fn accept(&mut self, sequence: u64, generation: u64, ids: &[u32]) {
        // The numbers are only meaningful inside the offer they came from. An
        // accept against a stale one is refused rather than applied to whatever
        // those numbers happen to mean now.
        if generation != self.import_generation {
            self.refuse(sequence, Refusal::Stale);
            return;
        }

        let mut taking = Vec::with_capacity(ids.len());
        for id in ids {
            match self.offer.iter().find(|(offered, _)| offered == id) {
                Some((_, candidate)) => taking.push(candidate.clone()),
                // All or nothing: a partly-applied set leaves somebody looking
                // at a screen that half agrees with the machine, with no way to
                // tell which half.
                None => return self.refuse(sequence, Refusal::UnknownId),
            }
        }

        {
            let mut held = self.shortcuts.write().expect("the list is never poisoned");
            if held.list().len() + taking.len() > crate::shortcuts::MAX_SHORTCUTS {
                drop(held);
                self.refuse(sequence, Refusal::Full);
                return;
            }
            for candidate in taking {
                if let Err(error) = held.adopt(
                    &candidate.name,
                    candidate.chord,
                    Origin::Imported,
                    Some(candidate.group),
                    candidate.recommended,
                ) {
                    // Checked for room already, so this is a name or a chord the
                    // rules turned down — said out loud rather than swallowed,
                    // and the rest still go in.
                    eprintln!("could not accept {}: {error}", candidate.name);
                }
            }
        }

        self.send_shortcuts();
        self.send_offer();
    }

    fn send_shortcuts(&mut self) {
        if !self.granted.shortcuts {
            return;
        }
        let entries: Vec<Shortcut> = self
            .shortcuts
            .read()
            .expect("the list is never poisoned")
            .list()
            .to_vec();

        self.shortcuts_generation += 1;
        self.out.send(Outbound::Snapshot {
            domain: Domain::Shortcuts,
            generation: self.shortcuts_generation,
            count: entries.len(),
        });
        for entry in entries {
            self.out.send(Outbound::Entry {
                domain: Domain::Shortcuts,
                generation: self.shortcuts_generation,
                record: Record::Shortcut(entry),
            });
        }
    }

    /// Reads the desktop again and offers whatever is not already recorded.
    ///
    /// A whole picture every time rather than a difference. This changes when
    /// somebody records or accepts something, which is rare and deliberate, and
    /// a difference would be more machinery than the thing is worth.
    fn send_offer(&mut self) {
        if !self.granted.import {
            return;
        }
        let (_source, candidates) = import::read();
        let fresh: Vec<Candidate> = {
            let held = self.shortcuts.read().expect("the list is never poisoned");
            candidates
                .into_iter()
                .filter(|candidate| !held.allows(&candidate.chord))
                .collect()
        };

        self.import_generation += 1;
        self.offer = fresh
            .into_iter()
            .enumerate()
            .map(|(position, candidate)| (position as u32 + 1, candidate))
            .collect();

        self.out.send(Outbound::Snapshot {
            domain: Domain::Import,
            generation: self.import_generation,
            count: self.offer.len(),
        });
        for (id, offer) in &self.offer {
            self.out.send(Outbound::Entry {
                domain: Domain::Import,
                generation: self.import_generation,
                record: Record::Candidate {
                    id: *id,
                    offer: offer.clone(),
                },
            });
        }
    }

    fn refuse(&self, sequence: u64, reason: Refusal) {
        self.out.send(Outbound::Refused { sequence, reason });
    }
}
