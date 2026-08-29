#!/usr/bin/env bash
#
# Rebuilds the typefaces in app/src/main/res/font.
#
# They are checked in, so this is not part of the build and does not run on CI.
# It exists because a binary nobody can regenerate is a binary nobody can
# change: without it, adding one character to the subset means guessing at how
# the last person made these.
#
# Needs fonttools (Arch: pacman -S python-fonttools).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
out="$here/../app/src/main/res/font"
licences="$here/../app/src/main/assets/licences"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The characters the app can put on screen.
#
# Wider than the strings that exist today, on purpose: shortcut labels are
# written by whoever makes the profile, and a subset cut to what is currently
# used would put a blank box in somebody's own label the first time they typed
# an accent. Latin-1 covers European names; the rest are the signs a control
# label reaches for. "Vol −" is why U+2212 is in this list, and the fact that
# it was missing the first time is why FontCoverageTest exists.
RANGE="U+0020-007E,U+00A0-00FF,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D,U+2026,U+2022,U+2190-2193,U+2212,U+00B1,U+2039,U+203A"

# Upstream, from Google Fonts. Both are SIL Open Font License 1.1.
fetch() { curl -sfL -o "$work/$2" "https://raw.githubusercontent.com/google/fonts/main/ofl/$1"; }
fetch "inter/Inter%5Bopsz,wght%5D.ttf"        inter-var.ttf
fetch "inter/OFL.txt"                         Inter-OFL.txt
fetch "spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf" spacegrotesk-var.ttf
fetch "spacegrotesk/OFL.txt"                  SpaceGrotesk-OFL.txt

# Subset first, then pin the weight. The other order overflows GPOS on Inter,
# because instancing a full variable font leaves more kerning than the table can
# address; subsetting drops the features and the problem with them.
build() {
  local src=$1 out_name=$2 family=$3 style=$4; shift 4
  pyftsubset "$work/$src" --unicodes="$RANGE" --layout-features='' \
    --no-hinting --output-file="$work/sub.ttf"
  fonttools varLib.instancer "$work/sub.ttf" "$@" -o "$out/$out_name" >/dev/null
  # The instancer keeps the variable font's default names, so without this the
  # files describe themselves as the wrong weight.
  python3 - "$out/$out_name" "$family" "$style" <<'PY'
import sys
from fontTools.ttLib import TTFont
path, family, style = sys.argv[1:4]
font = TTFont(path)
full = family if style == "Regular" else f"{family} {style}"
for nid, value in [(1, family), (2, style), (4, full),
                   (6, full.replace(" ", "")), (16, family), (17, style)]:
    font["name"].setName(value, nid, 3, 1, 0x409)
font.save(path)
PY
  printf '  %-28s %6d bytes\n' "$out_name" "$(stat -c%s "$out/$out_name")"
}

echo "building:"
build inter-var.ttf        inter_regular.ttf          Inter           Regular  wght=400 opsz=14
build inter-var.ttf        inter_medium.ttf           Inter           Medium   wght=500 opsz=14
build spacegrotesk-var.ttf space_grotesk_semibold.ttf "Space Grotesk" SemiBold wght=600

cp "$work/Inter-OFL.txt" "$work/SpaceGrotesk-OFL.txt" "$licences/"
echo "licences refreshed in assets/licences"
