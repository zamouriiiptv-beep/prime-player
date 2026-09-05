# Bundled typefaces

Castivio ships three families, all under the SIL Open Font License 1.1, which
permits bundling in a commercial application provided the licence travels with
the software and the fonts are not sold on their own.

| Family | Faces | Used for | Licence |
|---|---|---|---|
| IBM Plex Sans | 400, 500, 600, 700 | every Latin, Cyrillic and Greek language | `IBMPlexSans-OFL.txt` |
| IBM Plex Sans Arabic 1.1.0 | 400, 500, 600, 700 | Arabic-script languages | `IBMPlexSansArabic-OFL.txt` |
| Inter 4.0 | 400, 500, 600, 700 | the CASTIVIO wordmark, and nothing else | `Inter-OFL.txt` |

The interface used to be Inter for Latin and Plex Arabic for Arabic — two
families drawn by different hands, which showed as a heading that read bold in
English and a shade lighter in Arabic. Plex Sans and Plex Sans Arabic are one
family, so a weight now means the same thing in both scripts and the hierarchy
is size, weight and ink rather than partly an accident of script.

Inter stays for the wordmark alone. CASTIVIO is a logotype rather than a
heading, and redrawing its letterforms is a decision about the brand.

Four weights each and no italics: roughly 0.8, 1.0 and 1.6 MB of the APK.
Static faces rather than the variable fonts, because variable weight axes are
only honoured from API 26 and Castivio's minSdk is 21.

No Reserved Font Name is used in a modified form, and no file is modified —
they are the release binaries, unaltered.

**Release obligation.** OFL 1.1 requires the licence text to be distributed with
the software. Surfacing all three files in the About screen is on
`RELEASE_CHECKLIST.md`; shipping without it is a licence breach, not a polish
item.

`CastivioType` is the only place either family is named. Nothing else in the
codebase references a font file.
