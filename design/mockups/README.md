# Mockups

Every screen gets a mockup before it gets code. These files are the mockups, and
they are checked in for one reason: a review that only exists as an image cannot be
re-rendered when a token changes.

## What is here

| File | Screens |
|---|---|
| `shell-home.html` | Shell (rail, top bar), Home, the rail focused, a section the provider does not carry, phone Home, tablet section, and the state-language reference sheet |
| `activation-mac.html` | The first screen, where a subscription is added — phone landscape 873x393 and 800x360, television 960x540, in nine stress languages |
| `tv-sections.html` | Live TV, Movies and Series on a television — the browse screens a remote drives, categories then content |
| `gate-performance.html` | **A record, not a specification**: the loading gate and the measurement readout beside it, both removed when a section's load moved onto its card on Home (`home-loading.html`). Kept so the readout can be brought back from a drawing rather than from memory |
| `home-loading.html` | Home with a section's load drawn in its own card, the gate gone — pressed, waiting its turn, loaded, pressed before it has loaded, empty, failed, refreshing; phone 833x385 in Arabic and English, television 960x540, and the bar's colour against the reference's; how a refresh went, said on its own rail card — failed, until the next press, or answered, for a few seconds; and the subscription's trouble — a server that does not answer, a device with no internet, an account run out or refused — said on the disclaimer's line while the header keeps its date; and Home again while the rest of Live TV arrives, after it opened with its first category, its card showing the count alone. `?frame=sheet&set=a\|b\|c\|d\|e\|f` stacks the frames with their captions |
| `channels-lock.html` | Locking a channel: the four-digit password dialog (asking before a locked channel plays, locking, unlocking, and the three steps of changing the password), a lock at the end of a locked row, and the strip's Lock button lit — Unlock — for a locked channel; phone 851x393 and 800x360 in Arabic and English, television 960x540, and a portrait handset where the keypad stacks under the words. `?frame=sheet` stacks the frames with their captions |
| `channels-arriving.html` | Live TV opened by itself with its first complete category while the rest arrive: the rail growing silently under a remote, with nothing on the board saying the load goes on, and a load that stopped partway; phone 833x385 in Arabic and English, television 960x540. `?frame=sheet` stacks the frames with their captions |
| `movie-plot.html` | A film's plot, whole: the link "Show all" at the end of the Plot heading when the page had to cut the plot, and the small dialog that holds it — phone 833x385 in Arabic and English, television 960x540, a plot longer than the dialog's five lines scrolling inside it, and the link's states. `?frame=sheet` stacks the frames with their captions; `movie-plot-sheet.png` is that sheet rendered |
| `series-show.html` (superseded by `series-page.html`) | A series' page, seasons and episodes: the show beside its episodes (poster, facts, rating, Play that continues, plot; season chips and the chosen season's list), the remote on an episode, television 960x540, the details still arriving, a show the provider told nothing about, a show with no episodes, many seasons, and the seasons as the series poster's tag; phone 833x385 in Arabic. `?frame=sheet` stacks the frames with their captions; `series-show-sheet.png` is that sheet rendered |
| `series-page.html` | A series' page, second design, drawn from the owner's reference picture (`series-page-reference.png`): a rail with the way back and the sections (Overview, Seasons, Cast, Similar), the hero over the provider's wide picture (poster, name, year/genre/rating/seasons, plot, Play that continues, the favourite), two-line season chips with their episode counts, and episode rows (number, still, name, one line of plot, running time); television 960x540 in English, handset 833x385 in Arabic, scrolled to Seasons, the details arriving, a show the provider told nothing about, the cast and the similar shows, and a show with no episodes. `?frame=sheet` stacks the frames with their captions; `series-page-sheet.png` is that sheet rendered |
| `radio-board.html` | The radio: the channels' board with a grid of square station cards (logo over its own blurred colours, number, favourite heart, the category on the lists that mix categories), the plate that keeps the sound going (a bar under the wall on a handset, the panel at the board's end on television), the plate opened, the sleep timer, loading, a station with no logo, and an empty category; nothing the provider does not send (country, genre, bitrate, waveform) is drawn; phone 833x385 in Arabic, television 960x540. `?frame=sheet` stacks the frames with their captions; `radio-board-sheet.png` is that sheet rendered |
| `channels-arrange-bar-ar.html` | The channels board in arrange mode, and the band over it: as it is (five lines, 109dp) and as it is meant to be (one row: the word, one line of hint, Cancel, Save — 56dp, the buttons at the size they have), the board under it taller by what the band gives back; and the one-line hint in each state (nothing picked up, a row held, rows ticked, "all" open, the save failed); phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-arrange-bar-ar.png` is that sheet rendered |
| `channels-hide-bar-ar.html` | The channels board while it is choosing what to hide, and the band over it: as it is (the word, then two lines of hint, 59dp) and as it is meant to be (one row, the same as the reorder band's: the word in amber, one shortened line, Cancel, Hide selected -- 56dp, the buttons at the size they have), and the two states of the band (nothing marked yet, Hide selected dark; something marked, lit); phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-hide-bar-ar.png` is that sheet rendered |
| `channels-search-fields-ar.html` | The channels board's two search fields, the band's (channels) and the rail's (bouquets), as they are and as they are meant to be: the mark and the words 13dp from the pill's ends (the band's has 5, the rail's 8), the rail's words starting from the end the language starts from like the band's, the rail's hint "البحث في الباقات"; the two screens and the three pills close up; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-search-fields-ar.png` is that sheet rendered |
| `player-measurements-card-ar.html` | The player's measurements panel (development only) as it is -- twenty lines, 560dp wide over the picture and the controls -- and as a card the way Home's measurement card is: collapsed (two lines, 46dp, on the picture's left edge between the title and the playlist button, with the marks to open and to hide), opened by a press to every line there is and the two buttons, gone 20 seconds after the opening ends unless it is open; tap only, no focus; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `player-measurements-card-ar.png` is that sheet rendered |
| `channels-dark-logos-ar.html` | The channels list's logos drawn in a dark ink -- France 2 to 5, a coloured dot and a near-black digit on a near-black ground, of which only the dot is seen -- as they are, and with a light plate under the dark ones only (the logo's own slot, nothing moves; chosen once per picture from its opaque pixels' mean luminance), the list as it stands in both, and the four logos close up; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-dark-logos-ar.png` is that sheet rendered |
| `channels-no-logo-ar.html` | The mark of a channel that has no logo, as it is and as it is meant to be: the same crossed-out picture (no initials or generated tile -- removed from the list on purpose), larger in the list, in one of the product's four hues chosen from the channel's name so it keeps its colour wherever it is drawn, and on the board's picture plate -- large, in the channel's colour, where Castivio's own mark stood -- and beside the name under it; the two boards and the four colours close up; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-no-logo-ar.png` is that sheet rendered |
| `radio-list-ar.html` | The radio laid out as the channels' board is, replacing the wall of square cards (`radio-board.html`): the categories at the start as they are, the stations as rows in a frame (number plate, logo, name, and under it the category on the lists that mix categories, the favourite's heart at the end, the green disc on the one that sounds), where the preview stood a picture that says radio (a set with its waves) with the live badge when something sounds, and where the guide's card stood the plate of what is playing -- the station, previous / play or pause / next, the favourite, the sleep timer and the way into the full view -- taking the room of the five channel buttons; the board with a station sounding, with none chosen, and the plate in its four states; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `radio-list-ar.png` is that sheet rendered |
| `radio-headphones-ar.png` | The radio board's picture panel with the owner's headphones artwork in place of the drawn set, its black taken out so the ground is the application's own indigo (the third, calmer variant of `radio-headphones-variants.png`, approved); the rest of the screen is the radio board as it stands, 2340x1080 scaled to 2000x923 |
| `wait-states.png` | The loading screen of a channel, today and as it is now meant to be (approved): black with the title and a spinner after two seconds, against the channel that was on kept dim, the logo and name and number at once, a thin bar along the top, what is on the channel, the spinner and its line at two seconds, and at two and a half seconds the one sentence that says the provider is slow when the server has said nothing; four frames in one landscape sheet (the first, top right, is today) |
| `radio-buttons-ar.png` | The radio board's plate with its buttons as labelled tiles (approved): the station on a tile of its own; under it the station before, a wide play or pause in the board's primary gradient and the station after; and under those the favourite, the sleep timer and the way into the full view, each with its word. The rest of the screen is the radio board as it stands; `radio-buttons-before-after.png` stacks the board as it was above the drawing |
| `radio-world-ar.html` | The radio with a second source beside the subscription's: a directory of the world's stations. A two-ended switch at the head of the rail ("Subscription" / "World"); in World the rail lists Most listened, Favourites, Recently played and Genres, then the countries in the reader's language (the device's own first) with their station counts, and the stations are the same rows, most listened first, with rate and format (or the country, on lists that mix countries) on the second line; Favourites and Recently played hold the world's stations only, and a favourite that failed lately stays, dimmed; Genres swap the countries for genres; the list's loading, nothing-found, no-connection and directory-not-answering states; no directory credit under the rail (removed at the owner's request). Built: `WorldRadioScreen`. |
| `home-lively-ar.html` | Two directions for a livelier Home, drawn on the approved dark-glass one (`home-dark-ar.html`), and not on the coloured or glowing surfaces turned down on 2026-11-04: **A** the same cards showing what is there -- a strip of the channels watched last in the live card, mini posters on the films and series, station logos on the radio, the focused card filled violet as the channels' list fills its row, the three facts as one quiet line; **B** the live card as "continue watching" -- the last channel's logo on a white plate, its programme with the green progress bar, a Continue button and a row of recent channels. A drawing: logos and posters are examples; phone 833x385. Not built. |
| `home-lively-first-ar.html` | The first-run states of the two directions in `home-lively-ar.html`: the subscription just added and its sections loading (the same for A and B -- no history, so the live card returns to the approved one, "waiting" on the sections, the live count growing), and loaded with nothing watched yet (A: counts and a small Start watching chip; B: the "continue" card as an invitation, which is B's weakest state). A drawing; phone 833x385. Not built. |
| `home-channels-palette-ar.html` | Home in the channels screen's own colours, after the owner said the two directions of `home-lively-ar.html` were far from them: the reference (the approved dark glass), **C** the channels' surfaces -- slate card #20223A on a #0D0B22 ground, a violet picture panel for the live card, teal/violet at 16-18% in the two terms, flat, no glow -- and **D**, C with each section washed in its own hue and a plate under its glyph (the coloured surface of section 62, which section 68 turned down). A drawing; phone 833x385. Not built. |
| `home-resume-ar.png`, `home-resume-idle-ar.png` | **Approved 2026-11-05.** Home's live card in two states, drawn on the owner's own screenshot of the build (2340x1080) -- every pixel outside the card is theirs, untouched, and the card is drawn with the real `cardSurface` recipe (checked against the original card to within 4 of 255): **resume** (`home-resume-ar.png`) -- once something was watched: the logo and name of the channel watched last, what the stored guide says is on it with the aqua bar the channels' board draws, the four channels before it as plates, the violet "continue" button, and the live count as a line at the top; **not loaded** (`home-resume-idle-ar.png`) -- a subscription whose channels were never downloaded: the glyph, "load your channels", and a "load the channels" button, the other three sections "not downloaded yet". The loading, failed and loaded-without-history states are not drawn here: the code's own (`HeroLoad`) stands for them. Built from `HeroFaces.kt`. |
| `home-resume-full-ar.png`, `home-resume-idle-full-ar.png` | **Approved 2026-11-06**, replacing the two above for the live card after the owner's trial of the first build: on the device the card read as empty (a channel with no guide has no programme line or bar, so a gap stood over and under the logo; the invitation was a glyph alone). Drawn on the owner's new screenshots: the card's own ornament -- the glyph's frame echoed outward and the ribbons across the lower half, as it had before it carried anything -- is behind both faces, centred where the plain card's watermark is; the logo is 0.365 and the glyph 0.5 of the card's height; the earlier channels sit on quiet glass plates, all alike; and a channel the guide knows nothing about shows its number where the programme would be. No new colour. |
| `home-tiles-resume-ar.png`, `home-tiles-load-ar.png` | **Approved 2026-11-06.** The three small cards on Home -- films, shows, radio -- in the two states the large card has, drawn on the owner's screenshot (every other pixel is theirs) with the large card's approved faces beside them: **load** (`home-tiles-load-ar.png`) -- a section never downloaded keeps its name and "not downloaded yet" and takes the large card's violet button, shorter, at its empty end; **resume** (`home-tiles-resume-ar.png`) -- once something was watched in it: its name with its count (kept), what was watched last with a play mark, and the aqua bar of how far in it the viewer was (none for the radio: a live stream has no position), with the poster -- or the station's logo -- at the end. The titles and the posters in the drawing are examples; the screenshot has none. A press on a card with something to carry on from opens the section with that item started inside it, so Back lands on the section. |
| `channels-band-back-ar.html` | The board's band with the way back in it: an arrow at the band's trailing end -- the right, in every language, pointing right -- first from that edge, then the clock and the date, then the field; the application hides the system bars, so a finger had no visible way back from the channels, films, series and radio boards, which share the band; with the remote the arrow takes focus (Right from the field, Left to leave) and OK does what Back does -- a mode that is open closes first, then the board is left for Home; the channels' board at rest and with the arrow focused, and the radio's band with its order control; phone 833x385 in Arabic. `?frame=sheet` (the default) stacks the frames with their captions; `channels-band-back-ar.png` is that sheet rendered |
| `channels-band-brand-ar.html` | The mark and the name (Castivio) at the start of the board's band, as they are (at the frame's left edge, 14dp from it and 87dp from the section's name) and centred in the space between the bouquets' frame and the section's name, a little larger at two sizes to choose between (+15%: mark 27dp, name 15.8dp; +30%: mark 31dp, name 17.8dp); the space either side of the pair measured in dp. Phone 833x385 in Arabic, from the owner's photograph (2340x1080); the mark is the app's own picture |
| `channels-logo-plates-ar.html` | The channel logos that are cut off or lost, as they are (a pill-shaped plate the size of the slot, 26x13.6dp with 8dp corners, under a dark logo drawn 5% larger than the plate, so what stands outside it is dark on dark: TOONAMI reads TOONAM, CANAL+ loses its ends; and a dark logo that fills its box, ACTION, gets no plate) and as proposed (a rectangular plate with 3dp corners, the logo drawn whole inside it with a margin, the slot a fifth larger -- 32x16.5dp -- and a dark logo that fills its box plated too), beside a plate under every logo drawn only for comparison (a white logo is lost on it). Phone 833x385 in Arabic; the logos are stand-ins at their real proportions |
| `channels-mode-bar-fix-ar.html` | The band over the board while it arranges or chooses what to hide, as it is (its Arabic sentence set as a left-to-right paragraph, so "press" is at one end and "OK" at the other; Save and Cancel 48dp high, the band 54dp) and as it is meant to be (the band follows the reader's direction: in Arabic the word at the right end, its sentence running from it to the left and read in order with "OK" in place, the two buttons at the left end; the buttons 34dp, the band 40dp, the list 14dp higher), with the sentence of every state of the mode and of the hide band. Phone 833x385 in Arabic |
| `band-mark.html` | The mark and the name in the board's band, over the bouquets' column: side by side as in the owner's photograph, with the mark at four sizes (the picture untrimmed, and trimmed so the television is 18, 24 and 30dp in the same 34dp band); phone 833x385 in Arabic. `?frame=sheet` stacks the frames with their captions; `band-mark-sheet.png` is that sheet rendered. `band-mark-trimmed.png` is the mark's picture without its empty ground |
| `player-reference.png` | **The owner's picture, not a drawing of ours**: the player the chrome is built from, applied literally. A film in Arabic on a television: the way back, the title with five small words under it (the class of the picture ringed, then the codec, the shape, the sound's layout and its language), and at the end the heart, the list, the sound, the subtitles, the gear (lit, with its panel hanging under it — quality, audio track, subtitles, aspect ratio, playback speed, each with its value and an arrow) and the rest; the bar with the time gone at its start and the time left at its end; under it the playlist, the previous and next, the two ten-second jumps round the play control, and the screen's two pills (fill, lock). The layout is left to right whatever the language of the words. `design/mockups/` holds no HTML for it: the Compose screen is checked against it by rendering `PlayerScreen` at the picture's own geometry (1280x720dp at 1.306) |
| `player-channels.html` | The channel list inside the full-screen player, drawn before code: the list the channel was pressed in (same bouquet, order, hidden and locked channels as the board) opened over the picture from the "Playlist" pill at the foot, the only way in now that the top bar's list icon (which did what Back does) is gone; the board's own rows (number, name, quality badge, favourite star, lock, the green disc on the one playing, violet under the remote), opening scrolled to the playing channel, OK plays and closes; read a window at a time; television 960x540 in Arabic and English, handset 851x393, the first window arriving, and a list with only this channel. `?frame=sheet` stacks the frames with their captions |
| `player-channels-split-ar.html` | The channel list inside the full-screen player as a split and not an overlay, **approved and built** (LOCAL_CHANGES §104): the list is a column at the end edge -- 35% of a handset (280dp at least) and 40% of a television (340 to 400dp) -- and the picture, all of it in its own 16:9 and never stretched, is given the rest: 553x311dp on an 851x393 handset, 576x324dp on a 960x540 television, letterboxed top and bottom, where the overlay covered 38% of a 699x393 picture; the bars live in the picture's area, full height, and adapt by the player's own rules (chips cut between two chips, the end pills lose their words, and `TransportPlan` shares the transport row unequally -- the play control about 35dp off the centre line on a television -- rather than lay the fill over the lock); `phone-now` is the overlay as it was, for comparison. `?frame=sheet` stacks the frames with their rulers; rendered in `player-channels-split-ar.png`. |
| `player-slow-channel-ar.html` | What the viewer reads while a channel is slow to start, as it is and as it is meant to be -- **approved by the owner on 2026-11-12 and built** (LOCAL_CHANGES section 106). On `FR| TF1 4K` the card "Nothing arrived -- Retry" came about 13 seconds in over the channel that was on, and a little later the channel played by itself, because the engine behind the card is never stopped; before the card the line read "the primary player did not open this -- switching to the backup" for every viewer, not only in a development build. Drawn: **now** (that sentence at the hand-over, the card at about 13 s) and **meant**: the same "Connecting to the channel..." through the hand-over; no failure card while an engine is alive and has made progress -- the waiting screen of `wait-states.png` goes on, its bar moving, with no button; the card, unchanged in look and words, only when nothing arrived about 30 seconds after the press, and at once for a failure the engine itself reports. The 30 seconds is the owner's decision, not a measurement; a backup that takes over late is given ten seconds at the least. Phone 851x393 in Arabic; the channel has no guide, so no programme row. `?frame=sheet` (the default) stacks the frames with their captions; `player-slow-channel-ar.png` is that sheet rendered |
| `player-channel-controls-ar.html` | The controls of the player on a LIVE CHANNEL, as the owner decided them -- **approved by the owner on 2026-11-12 and built** (LOCAL_CHANGES section 107). Every control is in the top band or the bottom band, and the middle of the picture is empty in every frame (a ruler beside each frame of the sheet marks the three bands: 92dp, 134dp empty and 167dp on a handset). Kept: the playlist, previous, play and pause, next, fill the screen, and lock (handset and tablet only). Gone from a viewer's build: the "..." button (it stays in a debug build as the door to the statistics panel) and, where the stream has one audio track, the audio button; no brightness and no subtitles. New: **"إرجاع" (time shift)**, a pill at the end of the transport drawn only where the channel has an archive (`PlayerRequest.supportsTimeshift`); pressed, it lights and the bar's row gives way to four options -- from the start of the programme, 15 minutes before, 30 minutes before, an hour before -- instead of a panel over the picture; once chosen, the bar says "متأخّر 15:00 عن المباشر", the existing back-to-live pill appears beside it, and the LIVE chip turns grey (a small proposal, drawn so it can be refused). The build also requires, as the owner asked when approving: the pill only once the archive has been read and proved to reach back, the places only for what it really holds, one engine through every move between the live edge and the archive, and the picture's size and place untouched by the strip -- and adds one frame, `phone-failed`. Frames: handset `phone-now`, `phone-new`, `phone-shift`, `phone-strip`, `phone-shifted`, `phone-failed`; television `tv-now`, `tv-new`, `tv-strip`, `tv-shifted` (no lock there). `?frame=phone`, `?frame=tv` or `?frame=sheet`; rendered in `player-channel-controls-ar.png` (handset) and `player-channel-controls-tv-ar.png` (television) |
| `movies-poster-rating-ar.html` | The films' wall with the rating the film's page shows laid on each poster, three shapes drawn and **A chosen by the owner (2026-11-13)**: **A** the gold star and the figure on the running-time tag's own dim pill at the poster's top-end corner (the one corner no other mark of the card uses), **B** a gold plate in dark ink (IMDb's own mark: the loudest, and the first thing the eye meets on two hundred thousand posters), **C** the rating inside the running-time tag (nothing new on the poster, but it moves with the tag, and most films have no running time). A's states: a film with no rating or a zero draws nothing, ten, with a running time (two marks in opposite corners), no poster, a television card with focus (the mark grows with the card), and a card that has its top-start "playing" badge, which wins -- no rating is drawn beside it. The foot says what the code needs: the list's rating was read by nothing (parser, a `media.rating` column and its migration, `Movie.rating`, one parameter on the card, a spoken sentence); phone wall of 5x2 cards 112dp wide in Arabic. `?frame=now\|a\|b\|c\|states` one frame; `?frame=sheet` (the default) stacks the frames with their captions; `movies-poster-rating-ar.png` is that sheet rendered. Built: A (`PosterRating`). |
| `movie-cast-more-ar.html` | A film's cast line, whole: the owner's Ben-Hur screenshot (handset 833x385) as it is -- the cast cut with an ellipsis in the middle of a name, and over it the test build's request line (`ask ... try 1`), which was drawn in the page's corner over the last line -- and as meant: "Show all" at the end of the cast line itself (the plot's own link, shown only where the line was cut; the director's line by the same rule), a press opening the plot's dialog with the film's name for its title and "Cast:" in bold with every name for its body, the request line moved onto the poster's lower edge on the tags' dim ground (a user's build draws none); television 960x540 with the focus on the link, a narrow page that scrolls anyway with the credits whole (no cut, no link), and the link's states (fits, cut, remote focus, English, a cut director). A scrolling box on the page was the other way to do it and is not drawn: a remote cannot scroll text that takes no focus, and the scroll fights the page's own on a narrow surface. `?frame=sheet` (the default) stacks the frames with their captions, `&set=a|b` half of it; `movie-cast-more-ar.png` is that sheet rendered. **Built 2026-11-13** on the owner's order ("عدل و ادفع و لا تبن"; he asked for no more pictures): `Credit`, `PagePoster`. |

## What a mockup is, after the sizing migration

A mockup is **a drawing at one device geometry**, and that is still exactly what it
should be. The runtime no longer holds a table of device geometries, but the shares in
`castivioMetrics` are *read off* the 1280×720 reference — which is the 960×540
television drawing at 4/3 — so a file rendered at 873×393 or 960×540 still shows what
Compose lays out there, to the dp. Nothing in this directory had to change for the
migration, and the numbers in these files are still the record the code is judged
against.

`home-frames.html` is the one exception, and it is kept **as a historical record
rather than a specification**. It exists to demonstrate the four-row `CastivioFrame`
table — it transcribes that table and says so on the page — and that table was deleted
in stage 5 of the migration (see `UI_ARCHITECTURE.md`, "Where it is applied"). Read it
as a drawing of what was replaced. It is not a description of how any Castivio screen
is sized today, and a number taken out of it and put into code would be a device table
coming back through a picture.

## Rendering

Each file renders one frame at a time, chosen by `?frame=`, at exactly the logical
size of the device. Render at the device's scale factor and the PNG is pixel-accurate
to what Compose lays out — a 1080p television is 960×540 dp at 2×.

```sh
CHROME=/path/to/chrome            # any Chromium; headless_shell avoids window chrome
render() {  # frame  width  height  scale  out
  "$CHROME" --headless --no-sandbox --disable-gpu --hide-scrollbars \
    --force-device-scale-factor="$4" --window-size="$2,$3" \
    --screenshot="$5" --virtual-time-budget=1500 \
    "file://$PWD/shell-home.html?frame=$1"
}

render tv-home         960  540 2    tv-home.png          # 1920x1080
render tv-rail         960  540 2    tv-rail.png
render tv-empty        960  540 2    tv-empty-section.png
render phone-home      412  892 3    phone-home.png
render tablet-section 1280  800 1.5  tablet-movies.png
render states          960 1000 2    state-language.png   # a reference sheet, not a device
```

`activation-mac.html` takes the same `?frame=`, plus `?lang=` for the stress
languages, and `&spec=1` overlays the measures and the safe area:

```sh
render() { ... "file://$PWD/activation-mac.html?frame=$1"; }   # same helper

render phone-873  873 393 2   mac-phone-873.png    # 1080x2400 at 2.75, landscape
render phone-800  800 360 2   mac-phone-800.png    # 720x1600 at 2.0, the shortest
render tv         960 540 2   mac-tv.png           # 1920x1080 at 2.0
```

`tv-sections.html` is television only, so all three of its frames are one size:

```sh
render() { ... "file://$PWD/tv-sections.html?frame=$1"; }      # same helper

render live    960 540 2  tv-live.png      # categories, channels, what is on
render movies  960 540 2  tv-movies.png    # categories, a grid of titles
render series  960 540 2  tv-series.png    # categories, a grid with seasons
```

## Measuring

A layout that has to fit is not a layout to eyeball, so `measure.js` reads the
element boxes out of the DOM and checks eight things on every frame **in every
stress language**: the document is no bigger than its frame, nothing paints
outside it, no text overflows its box, every string key actually resolves, the
address fits the row that holds it, no script's glyphs are taller than the line
box holding them, the standing notice is still on screen, the header is still
one row, every control still meets its touch or D-pad minimum, and the QR still
has the module pitch a camera needs.

```sh
npm i -g playwright          # the browser is already here; only the driver is missing
node measure.js              # every frame x every language
node measure.js --lang en,de --frame tv
node measure.js --state all  # x every transient state, in the four worst languages
node measure.js --shots ./out
```

Exit code is non-zero when any frame in any language fails, so it works as a gate.

A resting screen is not the whole claim. `--state all` measures the transient
ones too, because each changes the layout: a spinner and a longer verb widen the
refresh button, a status sentence appears under the actions and can wrap, and a
focus ring is drawn outside the control it belongs to. It runs in `en`, `ar`,
`de` and `th` — the baseline, the mirror, the widest and the tallest — because
nine languages times eight states is a slow run that says nothing the worst four
do not.

### The stress languages

English alone cannot check a claim about fitting. Nine languages are measured,
each for a different reason: `en` baseline, `ar` right-to-left and bidi, `de`
and `fi` expansion, `th` and `hi` and `bn` line boxes taller than Latin's, `ja`
and `zh` line breaking without spaces.

This needs the Noto faces Android ships — Noto Sans, and the Arabic, Thai and
Devanagari families — or Thai and Devanagari fall back to whatever the machine
has and their line boxes, the thing being measured, stop being the device's.
`measure.js` says so if they are missing. CJK falls back to WenQuanYi here,
which is adequate because a CJK glyph is one em wide by definition.

Three of those four checks caught a real defect while `activation-mac.html` was
being drawn. The worst was `flex:1 1 auto` on the note column: a flex basis taken
from a paragraph's max-content width wins the space and shrinks its neighbours,
which left the MAC card at 238dp when the address needed 323 — plausible in the
stylesheet, clipped on screen.

Four of the checks were wrong before they were right, and the pattern is worth
knowing because it recurs:

- **The address was measured against the box it sits in.** A block-level box is
  as wide as its parent, so it always "fits" however badly it overflows; a
  content-sized box is exactly as wide as its text, so it always reports zero
  slack. Room is now the row's content width less the label, the copy control
  and the gaps — what the address could actually grow into.
- **Line height was measured against `line-height:normal`.** That is the font's
  *recommended* spacing, and Noto Sans Arabic recommends 46dp for a 22sp string,
  so every Arabic line "failed" while rendering perfectly. The yardstick is the
  glyphs' ink, from Canvas TextMetrics.
- **A missing string rendered as the word "undefined".** `s[key] || S.en[key]`
  returns undefined for a key neither table has, and assigning that to
  textContent writes text — so an entire button rendered blank and every check
  passed, because an empty 48dp button is exactly 48dp tall. A harness that
  certifies an empty screen is worse than no harness. Missing keys are now
  conspicuous on screen and fail the run.
- **The worst-case summary filtered on a value that could never match.** The
  probe returned a key called `frame` holding the viewport box, and the caller
  spread it over the `frame` naming the device. The summary was dead for three
  runs and said nothing rather than saying something wrong, which is why it took
  three runs to notice.

A check that fails a healthy layout is worse than no check, and a check that
silently measures nothing is worse than both.

The PNGs are deliberately **not** committed: they are five megabytes that can be
regenerated in a second, and a stale image in the repository is worse than none.

## Rules these files follow

- Sizes are dp, matching `Spacing`, `Sizing` and `Radius` in `:core:design`.
- Colours are the real tokens from `Color.kt`. A hex value that is not in the
  palette is a defect in the mockup, not a design decision.
- The safe area on television is 5%, which is `Spacing.tvOverscan` at 1080p.
- Type follows `Type.kt`. The rendering font is whichever grotesque the rendering
  machine has; the app itself uses the platform sans.
