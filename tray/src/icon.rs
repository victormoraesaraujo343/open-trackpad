//! The tray icon: the same trackpad-with-two-contacts mark as the Android app.
//!
//! Drawn in code rather than shipped as a bitmap. The shape is a rounded
//! rectangle and two dots, which is a few lines of arithmetic, and drawing it
//! means every panel size gets a crisp icon instead of a rescaled one. It also
//! keeps the crate free of an image decoder.
//!
//! Geometry is in a 0..1 square so it can be rasterised at any size. The
//! proportions match `android/app/src/main/res/drawable/ic_launcher_foreground.xml`,
//! widened slightly because a tray icon is a small square rather than a large
//! one with padding.

use ksni::Icon;

/// The lime the app uses.
pub const ACTIVE: (u8, u8, u8) = (0xA3, 0xE6, 0x35);

/// A muted grey for when there is nothing running to represent.
pub const IDLE: (u8, u8, u8) = (0x8A, 0x8A, 0x96);

/// Sizes panels commonly ask for. Offering several lets the desktop pick
/// rather than scale.
const SIZES: [u32; 4] = [22, 24, 32, 48];

/// The touch surface outline, as a centred rounded rectangle.
const PAD_HALF_WIDTH: f32 = 0.42;
const PAD_HALF_HEIGHT: f32 = 0.24;
const PAD_CORNER: f32 = 0.06;
const PAD_STROKE: f32 = 0.055;

/// The two contacts resting on it.
const DOT_OFFSET: f32 = 0.15;
const DOT_RADIUS: f32 = 0.075;

/// Signed distance to the edge of a rounded rectangle centred on (0.5, 0.5).
///
/// Negative inside, positive outside, and the magnitude is the distance — which
/// is what makes both the outline and the anti-aliasing fall out of one number.
fn rounded_rect_distance(x: f32, y: f32) -> f32 {
    let dx = (x - 0.5).abs() - (PAD_HALF_WIDTH - PAD_CORNER);
    let dy = (y - 0.5).abs() - (PAD_HALF_HEIGHT - PAD_CORNER);
    let outside = dx.max(0.0).hypot(dy.max(0.0));
    let inside = dx.max(dy).min(0.0);
    outside + inside - PAD_CORNER
}

fn circle_distance(x: f32, y: f32, centre_x: f32, centre_y: f32) -> f32 {
    (x - centre_x).hypot(y - centre_y) - DOT_RADIUS
}

/// Turns a signed distance into coverage, softening exactly one pixel's worth
/// so edges are smooth at every size.
fn coverage(distance: f32, pixel: f32) -> f32 {
    (0.5 - distance / pixel).clamp(0.0, 1.0)
}

/// How much of the mark covers the pixel centred at (x, y).
fn mark_coverage(x: f32, y: f32, pixel: f32) -> f32 {
    // The outline is the band either side of the rectangle's edge.
    let outline = coverage(rounded_rect_distance(x, y).abs() - PAD_STROKE / 2.0, pixel);
    let left = coverage(circle_distance(x, y, 0.5 - DOT_OFFSET, 0.5), pixel);
    let right = coverage(circle_distance(x, y, 0.5 + DOT_OFFSET, 0.5), pixel);
    outline.max(left).max(right)
}

/// Rasterises the mark at one size, in the ARGB32 the tray protocol expects.
fn render(size: u32, (red, green, blue): (u8, u8, u8)) -> Icon {
    let pixel = 1.0 / size as f32;
    let mut data = Vec::with_capacity((size * size * 4) as usize);

    for row in 0..size {
        for column in 0..size {
            let x = (column as f32 + 0.5) * pixel;
            let y = (row as f32 + 0.5) * pixel;
            let alpha = (mark_coverage(x, y, pixel) * 255.0).round() as u8;
            // Premultiplied, so anti-aliased edges blend against any panel
            // colour instead of haloing against a wrong assumed background.
            let scale = |channel: u8| (channel as u16 * alpha as u16 / 255) as u8;
            data.extend_from_slice(&[alpha, scale(red), scale(green), scale(blue)]);
        }
    }

    Icon {
        width: size as i32,
        height: size as i32,
        data,
    }
}

/// The mark at every size a panel is likely to want.
pub fn pixmaps(colour: (u8, u8, u8)) -> Vec<Icon> {
    SIZES.into_iter().map(|size| render(size, colour)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_size_is_offered_with_the_right_amount_of_data() {
        for icon in pixmaps(ACTIVE) {
            assert_eq!(
                icon.data.len(),
                (icon.width * icon.height * 4) as usize,
                "{}x{} has the wrong buffer length",
                icon.width,
                icon.height
            );
        }
    }

    #[test]
    fn the_mark_is_drawn_rather_than_being_blank_or_solid() {
        let icon = pixmaps(ACTIVE)
            .into_iter()
            .find(|icon| icon.width == 48)
            .expect("48px is one of the offered sizes");
        let opaque = icon.data.chunks(4).filter(|pixel| pixel[0] > 200).count();
        let total = icon.data.len() / 4;

        assert!(opaque > 0, "nothing was drawn");
        assert!(opaque < total / 2, "the icon is a solid block, not a mark");
    }

    #[test]
    fn the_centre_is_hollow_and_the_contacts_are_not() {
        let icon = render(48, ACTIVE);
        let alpha_at = |x: u32, y: u32| icon.data[((y * 48 + x) * 4) as usize];

        // Between the two dots, inside the outline: nothing should be there.
        assert_eq!(alpha_at(24, 24), 0, "the middle of the pad should be empty");
        // On a dot.
        let dot_x = (0.5 - DOT_OFFSET) * 48.0;
        assert!(alpha_at(dot_x as u32, 24) > 200, "a contact is missing");
    }

    #[test]
    fn colour_reaches_the_pixels() {
        let icon = render(48, ACTIVE);
        let dot_x = ((0.5 - DOT_OFFSET) * 48.0) as u32;
        let offset = ((24 * 48 + dot_x) * 4) as usize;
        let [alpha, red, green, blue] = icon.data[offset..offset + 4] else {
            panic!("expected four channels");
        };
        assert!(alpha > 200);
        assert!(green > red && red > blue, "expected the lime the app uses");
    }
}
