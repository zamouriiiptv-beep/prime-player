# Bundled typefaces

Castivio ships two families, both under the SIL Open Font License 1.1, which
permits bundling in a commercial application provided the licence travels with
the software and the fonts are not sold on their own.

| Family | Faces | Used for | Licence |
|---|---|---|---|
| Inter 4.0 | 400, 500, 600, 700 | every Latin, Cyrillic and Greek language | `Inter-OFL.txt` |
| IBM Plex Sans Arabic 1.1.0 | 400, 500, 600, 700 | Arabic-script languages | `IBMPlexSansArabic-OFL.txt` |

Four weights each and no italics: 1.6 MB and 1.0 MB of the APK respectively.
Static faces rather than the variable fonts, because variable weight axes are
only honoured from API 26 and Castivio's minSdk is 21.

Neither Reserved Font Name is used in a modified form, and neither file is
modified — they are the release binaries, unaltered.

**Release obligation.** OFL 1.1 requires the licence text to be distributed with
the software. Surfacing both files in the About screen is on
`RELEASE_CHECKLIST.md`; shipping without it is a licence breach, not a polish
item.

`CastivioType` is the only place either family is named. Nothing else in the
codebase references a font file.
