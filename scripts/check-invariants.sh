#!/usr/bin/env bash
#
# Design invariants — the mechanical ones.
#
# UI_ARCHITECTURE.md §12 lists ten invariants. Some are held by the compiler
# (a sealed Route cannot be extended by a feature), some by review, and the ones
# below by this script. A rule that depends on someone remembering it is a rule
# that is already broken, so anything checkable is checked here and blocks the
# build.
#
# Fast on purpose: it is grep over the tree, no Gradle, no SDK. It runs on every
# commit alongside the unit tests.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

failures=0

fail() {
  echo "::error::$1"
  printf '%s\n' "$2"
  echo
  failures=$((failures + 1))
}

# The pre-modular screens are gone: the activation flow they were a draft of now
# lives in :feature:activation, over tested logic and out of the design system's
# components. This list is empty and must stay that way -- it only ever shrank, and
# there is nothing left to grandfather.
LEGACY=''

not_legacy() {
  # Reads paths on stdin. Nothing is exempt any more; kept as a seam so a future
  # migration has somewhere to put a temporary exemption, deliberately empty.
  cat
}

# ---------------------------------------------------------------- invariants 1, 2
# One visual language, one meaning per colour: the tokens live in :core:design and
# nothing else may invent a colour or a text style. A literal here is how a
# product ends up with nine greys and two brands.
hits=$(grep -rn --include='*.kt' -E 'Color\(0x|TextStyle\(' \
        app core feature playback data domain 2>/dev/null \
        | grep -v '^core/design/' | not_legacy)
if [ -n "$hits" ]; then
  fail "Invariant 1-2: colour or type literal outside :core:design" \
       "$hits

  Use a semantic token: CastivioTheme.colors.<name>, CastivioType.<style>.
  If no token fits, add one to :core:design -- that is the system working,
  not a workaround."
fi

# ------------------------------------------------------------------- invariant 9
# RTL is mandatory, so the APIs that hard-code a physical direction are banned
# outright rather than reviewed case by case. start/end always, left/right never.
hits=$(grep -rn --include='*.kt' -E \
        'absolutePadding|Arrangement\.Absolute|Alignment\.Absolute|padding\((left|right) *=' \
        app core feature playback data domain 2>/dev/null | not_legacy)
if [ -n "$hits" ]; then
  fail "Invariant 9: direction-absolute layout API" \
       "$hits

  These lay out identically in Arabic and English, which is the bug.
  Use start/end (Modifier.padding(start = ...), Arrangement.Start)."
fi

# ------------------------------------------------------------------- invariant 6
# One component, variants as parameters. A second declaration of a shared name is
# the beginning of two subtly different buttons.
SHARED='CastivioButton CastivioIconButton CastivioChip GlassCard GlassHeroCard
InteractiveGlassCard EmptyState ErrorState Skeleton SkeletonRow DelayedSpinner
CastivioNavRail CastivioActionBar CastivioTopBar SectionLabel
MediaCard ChannelCard MediaRow SectionHeader CastivioShell CastivioBottomBar
ScreenScaffold ScreenTopBar NowPlayingBadge WatchedTag LogoTile MetaChip
CastivioTextField
IdentityCapsule CopyButton QrPlate StatusChip StatusLine PlanCard'
for name in $SHARED; do
  count=$(grep -rn --include='*.kt' -E "^(internal |private )?fun $name\(" \
            app core feature playback data domain 2>/dev/null | wc -l)
  if [ "$count" -gt 1 ]; then
    fail "Invariant 6: '$name' is declared $count times" \
         "$(grep -rn --include='*.kt' -E "^(internal |private )?fun $name\(" \
              app core feature playback data domain)

  A component has one declaration and expresses its variants as parameters."
  fi
done

# --------------------------------------------- a control smaller than its target
# Two constants -- minTouchTarget 48, minTvTarget 56 -- and for a long time every
# interactive component in the design system pinned the first one on every frame.
# On a television that made every button in Castivio 8dp under the D-pad floor,
# and `CastivioIconButton` and `CastivioChip` were 36dp, under both.
#
# It was found twice by eye on a photograph and never by a check, because the
# check that existed asserted the floor for one screen's own metrics rather than
# for the components the screens are built from. Every interactive component here
# must ask which floor applies.
TARGETED='CastivioButton CastivioIconButton CastivioChip'
for name in $TARGETED; do
  body=$(awk "/^fun $name\(/,/^}/" core/design/src/main/java/com/castivio/core/design/components/Buttons.kt)
  if ! printf '%s' "$body" | grep -q 'Sizing.minTarget'; then
    fail "'$name' does not declare a minimum target" \
         "core/design/src/main/java/com/castivio/core/design/components/Buttons.kt

  An interactive component must floor its size at Sizing.minTarget(isTv). A
  control drawn smaller than the frame's floor is a control a remote cannot
  reliably land on, and 48dp is the *thumb* floor, not the D-pad one."
  fi
done

# -------------------------------------------------- cross-platform independence
# Not one of the ten, but the reason the ten are affordable: the layers below the
# presentation layer must stay portable, so a platform import in them is a defect
# even when it compiles. androidx.paging is allowed -- paging-common is plain
# Kotlin, and the alternative was reinventing it.
PURE='domain data/parsing core/navigation core/common playback/engine-api'
hits=$(grep -rn --include='*.kt' -E '^import (android\.|androidx\.)' $PURE 2>/dev/null \
        | grep -v 'androidx\.paging')
if [ -n "$hits" ]; then
  fail "Platform import in a platform-independent module" \
       "$hits

  These modules are compiled for every future platform. Put the platform
  detail behind an interface and implement it in an adapter module."
fi

# ------------------------------------------------------- 37 user-visible languages
# The product invariant is the number of languages a person can choose, and it is
# 37. It is deliberately NOT a number of Android resource directories: how many of
# those correct locale resolution needs is a question for a device, and the
# sentinel test answers it. Conflating the two is how "one Chinese entry" quietly
# becomes two.
LANGS=core/common/src/main/kotlin/com/castivio/core/common/locale/CastivioLanguage.kt
count=$(grep -cE '^    [A-Z][A-Za-z]*\(' "$LANGS" 2>/dev/null || echo 0)
if [ "$count" -ne 37 ]; then
  fail "The language set is $count entries, and the product invariant is 37" \
       "$LANGS

  A language is one visible choice. A script or regional variant is not a
  language -- add it to that language's \`variants\`, which is what the list is
  for. If 37 is genuinely changing, change it here and in the specification
  deliberately, in its own commit."
fi

# ------------------------------------------------- localisation is complete or it is not
# Thirty-seven languages is a promise, and "we added the language" is not the
# same claim as "every string exists in it". This checks the second one, over
# every locale directory, independently of any geometry measurement.
#
# It exists because the failure it catches is invisible: a missing key does not
# crash, it silently renders English inside an otherwise translated screen, and
# nobody testing in their own language will ever see it.
# Every user-facing bundle, not one of them.
#
# This checked `strings_activation.xml` alone for as long as that was the only
# bundle there was, and it stayed pointed there after a second one appeared --
# which is the third time in this file that a checker outlived the subset it was
# aimed at. Every bundle a feature ships is named here, and the check below
# walks all of them.
BUNDLES='feature/activation/src/main/res:strings_activation.xml
feature/licence/src/main/res:strings_licence.xml
app/src/main/res:strings_exit.xml'

# Well-formed XML, checked here rather than left to the build.
#
# AAPT rejects these too, but four minutes later and only when someone builds
# the Android modules. It has already cost two red CI runs, and the second one
# is the reason this now walks the whole tree.
#
# The first: a generator wrote a corrected set of files into the wrong directory
# and the check standing next to it validated the files it had just written
# rather than the ones in the tree.
#
# The second: this check was pointed at one module's res directory -- the
# activation feature's -- and a bad comment went into :app's. Green here, red in
# mergeDebugResources. Same mistake in a different costume, and the same lesson:
# a checker aimed at a subset is not a checker for the thing it is named after.
# It looks at every values* directory in every module now.
if command -v python3 >/dev/null 2>&1; then
  bad=$(python3 <<'PYXML'
import glob, xml.etree.ElementTree as ET
# Resource values *and* manifests. The manifests were outside this glob until a
# comment went inside an element's attribute list -- legal-looking, and not XML
# at all -- and this check reported all clear. Fourth time in this file that a
# checker outlived the subset it was pointed at; the lesson is cheaper to apply
# than to keep relearning.
seen = sorted(set(
    glob.glob("*/src/*/res/values*/*.xml") + glob.glob("*/*/src/*/res/values*/*.xml") +
    glob.glob("*/src/*/AndroidManifest.xml") + glob.glob("*/*/src/*/AndroidManifest.xml")
))
if not seen:
    print("  no resource files were found at all, which means this glob is wrong")
for f in seen:
    try:
        ET.parse(f)
    except Exception as e:
        # "--" inside a comment is the one that keeps happening: legal in prose,
        # illegal in XML, and invisible until a build says so.
        print("  %s: %s" % (f, e))
PYXML
)
  if [ -n "$bad" ]; then
    fail "A string resource is not well-formed XML" "$bad"
  fi
fi

problems=''
for bundle in $BUNDLES; do
 RES=${bundle%%:*}
 NAME=${bundle#*:}
 BASE=$RES/values/$NAME
 if [ ! -f "$BASE" ]; then
   fail "A declared string bundle is missing" "$BASE"
 fi

 # Every <string> and <plurals> name the default locale declares, minus the
 # ones marked untranslatable.
 want=$(grep -oE '<(string|plurals) name="[a-z_0-9]+"( translatable="false")?' "$BASE" \
         | grep -v 'translatable="false"' \
         | sed -E 's/.*name="([a-z_0-9]+)".*/\1/' | sort -u)

 for file in "$RES"/values*/$NAME; do
  dir=$(basename "$(dirname "$file")")
  for key in $want; do
    # Present, and with something between the tags. An empty translation is the
    # one a hurried pass leaves behind, and it renders as a blank control.
    line=$(grep -oE "<(string|plurals) name=\"$key\">.*" "$file" | head -1)
    if [ -z "$line" ]; then
      problems="$problems
  $dir  missing   $key"
    elif echo "$line" | grep -qE "<string name=\"$key\"></string>|<string name=\"$key\">[[:space:]]*</string>"; then
      problems="$problems
  $dir  empty     $key"
    fi
  done
  # A plural with no <item> is present, non-empty and useless.
  if grep -q '<plurals' "$file" && ! grep -q '<item quantity=' "$file"; then
    problems="$problems
  $dir  no plural forms"
  fi
  # Markers from the mockup harness and from unfinished work. Any of these
  # reaching a resource file means a string was shipped before it was written.
  if grep -qE 'TODO|FIXME|⟨|XXX|%s%s' "$file"; then
    problems="$problems
  $dir  placeholder left in the file"
  fi
 done

 # A bundle present in the default locale and absent from a locale directory
 # the sibling bundle has. A whole missing file renders the whole screen in
 # English, and the per-key loop above cannot see a file that is not there.
 for peer in "$RES"/values-*; do
   if [ ! -f "$peer/$NAME" ]; then
     problems="$problems
  $(basename "$peer")  missing the whole $NAME"
   fi
 done
done

if [ -n "$problems" ]; then
  fail "A string bundle is not complete in every locale" \
       "$problems

  Every key in values/ must exist, non-empty, in every locale directory.
  Passing the stress-language subset says nothing about the others."
fi

# Every language the model declares must have somewhere to read its strings
# from. This is the join between the Kotlin list and the resource tree, and
# without it the two drift silently -- a language in the picker whose
# directory nobody created falls back to English and looks translated.
# Two ways a qualifier is spelled in the model. Both are collected, because
# checking only the explicit ones would leave 32 of the 37 unchecked -- and
# those are exactly the languages nobody thinks about.
#
#   explicit   one("id", "values-in")   or   LanguageVariant("pt-BR", "values-pt")
#   implicit   one("de")                ->   values-de
#
# `grep -v '\$'` drops the "values-$code" template in the helper itself; it is a
# pattern, not a directory.
explicit=$(grep -oE '"values(-[^"]*)?"' "$LANGS" | tr -d '"' | grep -v '\$')
implicit=$(grep -oE 'one\("[a-z]+"\)' "$LANGS" | sed -E 's/one\("(.*)"\)/values-\1/')
for qualifier in $(printf '%s\n%s\n' "$explicit" "$implicit" | sort -u); do
 # Every bundle, not whichever one the previous loop happened to leave in $RES.
 # It read a leftover variable for exactly one commit, which was long enough to
 # report thirty-nine failures against files that were never meant to exist.
 for bundle in $BUNDLES; do
  RES=${bundle%%:*}
  NAME=${bundle#*:}
  if [ ! -f "$RES/$qualifier/$NAME" ]; then
    fail "A declared language has no strings" \
         "$RES/$qualifier/$NAME

  CastivioLanguage names this directory, and it does not exist. A language
  in the picker with no resources falls back to English and looks translated."
  fi
 done
done

# ---------------------------------------- a translation that is not a translation
#
# A key can be present, non-empty, well-formed and still be the English string
# copied across. The completeness check cannot see it and neither can a reader
# who does not speak the language.
#
# Checked on the two sentences a user reads rather than on every key: the QR
# caption and the legal line. Short strings legitimately match across languages
# -- "Castivio", "QR", "MAC" -- and a blanket rule on those produces noise that
# gets switched off, which is worse than not having the rule.
if command -v python3 >/dev/null 2>&1; then
  untranslated=$(python3 <<'PYCOPY'
import glob, io, os, re
# The sentences a user actually reads, per bundle. Not every key: short
# strings legitimately match across languages -- "Castivio", "QR", "MAC" --
# and a blanket rule produces noise that gets switched off.
BUNDLES = {
    "feature/activation/src/main/res": (
        "strings_activation.xml", ("qr_caption", "legal_player_only")),
    "feature/licence/src/main/res": (
        "strings_licence.xml", ("licence_qr_caption", "licence_legal",
                                "licence_status_none", "licence_status_revoked")),
    "app/src/main/res": ("strings_exit.xml", ("exit_message",)),
}

def values(path):
    text = io.open(path, encoding="utf-8").read()
    return {
        m.group(1): re.sub(r"\s+", " ", m.group(2)).strip()
        for m in re.finditer(r'<string name="([a-z_0-9]+)"[^>]*>(.*?)</string>', text, re.S)
    }

for res, (name, watch) in sorted(BUNDLES.items()):
    english = values(os.path.join(res, "values", name))
    for f in sorted(glob.glob(os.path.join(res, "values-*", name))):
        for key, value in values(f).items():
            if key in watch and value == english.get(key):
                print("  %s/%s: %s is still the English text"
                      % (os.path.basename(os.path.dirname(f)), name, key))
PYCOPY
)
  if [ -n "$untranslated" ]; then
    fail "A locale carries the English string where a translation belongs" \
         "$untranslated"
  fi
fi

# --------------------------------------------- a translation that lost its slot
#
# A parameterised string is a contract between the resource and the call site:
# `licence_status_expired_on` takes one date, and every one of the 38 other
# bundles has to take exactly one too. A translator who drops the `%s` produces a
# sentence with the fact missing from it; one who writes it twice crashes
# `getString` with an argument-index error at the moment the state is reached,
# which on this screen is the moment somebody's licence runs out.
#
# Neither is visible to the completeness check above -- the key is present and
# non-empty in both cases -- and neither is visible to a reader who does not
# speak the language. It is pure arithmetic, so it is checked here rather than
# left to review.
#
# `%%` is an escaped percent and carries no argument, so it is removed before
# counting; positional (`%1$s`) and plain (`%s`) forms both count as one.
if command -v python3 >/dev/null 2>&1; then
  slots=$(python3 <<'PYSLOT'
import glob, io, os, re

BUNDLES = {
    "feature/activation/src/main/res": "strings_activation.xml",
    "feature/licence/src/main/res": "strings_licence.xml",
    "app/src/main/res": "strings_exit.xml",
}

SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")

def slots(path):
    text = io.open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(
        r'<(string|plurals) name="([a-z_0-9]+)"[^>]*>(.*?)</\1>', text, re.S
    ):
        # A plural's forms are one contract: every <item> takes the same
        # arguments, so the maximum across them is the count for the key.
        bodies = re.findall(r"<item[^>]*>(.*?)</item>", m.group(3), re.S) or [m.group(3)]
        out[m.group(2)] = max(len(SPEC.findall(b.replace("%%", ""))) for b in bodies)
    return out

for res, name in sorted(BUNDLES.items()):
    english = slots(os.path.join(res, "values", name))
    for f in sorted(glob.glob(os.path.join(res, "values-*", name))):
        for key, count in sorted(slots(f).items()):
            want = english.get(key)
            if want is not None and count != want:
                print("  %s/%s: %s takes %d argument(s), English takes %d"
                      % (os.path.basename(os.path.dirname(f)), name, key, count, want))
PYSLOT
)
  if [ -n "$slots" ]; then
    fail "A translation does not take the arguments its English original does" \
         "$slots"
  fi
fi

# ------------------------------------------- a translation in the wrong script
#
# The English screen rendered an Arabic sentence for a whole review cycle. That
# particular cause -- :app shadowing a library string -- is checked below, but
# the *symptom* is worth catching on its own, because there are other ways to
# reach it: a copy-paste into the wrong file, a merge that takes the wrong side,
# a translator handed the wrong bundle.
#
# Arabic script outside an Arabic-script locale is never right, and it is the one
# leak this product has actually shipped. Cheap to check, so it is checked.
if command -v python3 >/dev/null 2>&1; then
  leaked=$(python3 <<'PYSCRIPT'
import glob, io, os, re, unicodedata
ARABIC_LOCALES = {"values-ar", "values-fa", "values-ur", "values-ps", "values-sd", "values-ug"}
for f in sorted(glob.glob("*/*/src/main/res/values*/*.xml")):
    d = os.path.basename(os.path.dirname(f))
    if d in ARABIC_LOCALES:
        continue
    text = io.open(f, encoding="utf-8").read()
    for m in re.finditer(r'<(string|plurals) name="([a-z_0-9]+)"[^>]*>(.*?)</\1>', text, re.S):
        value = re.sub(r"<[^>]+>", "", m.group(3))
        for ch in value:
            if ch.isalpha() and unicodedata.name(ch, "").startswith("ARABIC"):
                print("  %s: %s carries Arabic script -- %s" % (f, m.group(2), value[:48]))
                break
PYSCRIPT
)
  if [ -n "$leaked" ]; then
    fail "A non-Arabic locale contains Arabic text" \
         "$leaked

  This is the shape of the bug the English QR caption had. Check which module
  the string is resolving from before editing the translation."
  fi
fi

# ------------------------------------ no two modules may declare the same string
#
# Android merges every module's resources into one table. Two modules declaring
# the same name is not an error and not a warning -- one silently wins, and
# which one depends on the merge order. That is exactly how the English QR
# caption came to render in Arabic.
#
# The :app case below is the one that shipped; this is the general rule, and it
# is what makes a new feature module safe to add. `:feature:licence` prefixes
# every one of its names for this reason.
if command -v python3 >/dev/null 2>&1; then
  collisions=$(python3 <<'PYDUP'
import collections, glob, io, os, re
owners = collections.defaultdict(set)
for f in glob.glob("*/src/main/res/values/*.xml") + glob.glob("*/*/src/main/res/values/*.xml"):
    module = f.split("/src/")[0]
    text = io.open(f, encoding="utf-8").read()
    for m in re.finditer(r'<(?:string|plurals) name="([A-Za-z_0-9]+)"', text):
        owners[m.group(1)].add(module)
for name, mods in sorted(owners.items()):
    if len(mods) > 1:
        print("  %s is declared by %s" % (name, ", ".join(sorted(mods))))
PYDUP
)
  if [ -n "$collisions" ]; then
    fail "Two modules declare the same string resource" \
         "$collisions

  The merged table keeps one of them and the choice is not yours. Prefix the
  name with the feature that owns it, or move the string to a module both
  depend on."
  fi
fi

# --------------------------------- :app must not shadow a module's user-visible string
#
# Resource merging lets the application module override a library module's value
# for the same name and qualifier. That is not a warning; it is silent, and it
# shipped: `:app` held a leftover Arabic `qr_caption` in the default bucket, so
# the activation screen read English from the Arabic string and every other
# language read correctly from the feature module. It survived 39 locale files,
# a completeness check and a placement gate, because none of them look at two
# modules at once.
#
# A user-visible string belongs to the module that draws it.
app_defaults='app/src/main/res/values'
if [ -d "$app_defaults" ]; then
  shadowed=$(
    comm -12 \
      <(grep -ho 'name="[A-Za-z_0-9]*"' "$app_defaults"/*.xml 2>/dev/null |
          sed 's/name="//;s/"//' | sort -u) \
      <(cat feature/*/src/main/res/values/*.xml core/*/src/main/res/values/*.xml 2>/dev/null |
          grep -o 'name="[A-Za-z_0-9]*"' | sed 's/name="//;s/"//' | sort -u)
  )
  if [ -n "$shadowed" ]; then
    fail ":app declares a string a feature or core module already owns" \
         "$(echo "$shadowed" | sed 's/^/  /')

  The application module's value wins at merge time, in that bucket only, so the
  string is wrong in one language and right in the rest. Delete it from :app."
  fi
fi

# ------------------------------------------------- the licence cannot license itself
# Not one of the ten, and the most expensive one to get wrong. A local trial grantor
# belongs to a development build and nowhere else; the type system already says so
# (Licensing.Production has no field for one), and this catches the other half -- a
# reference that smuggles it somewhere the type never travels.
hits=$(grep -rn --include='*.kt' 'LocalEntitlementSource' \
        app core feature playback data domain 2>/dev/null \
        | grep -v '^data/entitlement/src/main/java/com/castivio/data/entitlement/LocalEntitlementSource.kt:' \
        | grep -v '^data/entitlement/src/main/java/com/castivio/data/entitlement/di/EntitlementModule.kt:' \
        | grep -v '^data/entitlement/src/test/')
if [ -n "$hits" ]; then
  fail "The local trial grantor is referenced outside its own module wiring" \
       "$hits

  LocalEntitlementSource may only be constructed inside Licensing.Development,
  in EntitlementModule. A release build must have nothing that can grant it a
  licence -- see RELEASE_CHECKLIST.md."
fi

# And the wiring itself must gate on the build type. A Development licensing chosen
# unconditionally is a release APK that licenses itself, which is the one bug in this
# repository that would not show up in any test.
module='data/entitlement/src/main/java/com/castivio/data/entitlement/di/EntitlementModule.kt'
if [ -f "$module" ]; then
  if grep -q 'Licensing.Development' "$module" && ! grep -q 'BuildConfig.DEBUG' "$module"; then
    fail "Licensing.Development is chosen without checking the build type" \
         "$module

  Development licensing must be selected behind BuildConfig.DEBUG. Without it a
  release build grants its own trials."
  fi
fi

# ------------------------------------------------- copy that says it is not finished
# A bracketed stand-in is how the licence screen's legal line lived for three
# commits, and it was correct to ship it that way while the wording was a legal
# question rather than a design one. It is not correct to ship it by accident, and
# the difference between the two is entirely whether anybody remembers.
#
# So the tree is asked instead. A string that opens with a bracket, or that
# announces itself as unwritten, fails the build in every language.
hits=$(grep -rn --include='strings*.xml' -E \
        '<(string|item)[^>]*>[[:space:]]*[\[［]|to be written|TO BE WRITTEN|PLACEHOLDER|placeholder text' \
        app core feature 2>/dev/null)
if [ -n "$hits" ]; then
  fail "A user-visible string is still a placeholder" \
       "$hits

  Ship the wording or do not ship the screen. If the copy genuinely cannot be
  written yet, the screen is not finished -- see CLAUDE.md, 'Production code only'."
fi

# ---------------------------------------------- a debug affordance that is not gated
# The state board and the forced entitlement exist so that every licence state can be
# reached on a real device. Both are compiled out of a release build by a constant
# that is false -- and only because each file checks it. A file that grows a second
# entry point without the check would ship a debug menu in a store build, which is the
# kind of thing nobody finds until a screenshot of it turns up.
for gated in \
  'app/src/main/java/com/castivio/tv/debug/LicenceStateBoard.kt' \
  'feature/licence/src/main/java/com/castivio/feature/licence/LicenceRoute.kt' \
  'feature/licence/src/main/java/com/castivio/feature/licence/DebugFixtures.kt' \
  'feature/activation/src/main/java/com/castivio/feature/activation/DebugFixtures.kt'
do
  [ -f "$gated" ] || continue
  if ! grep -q 'BuildConfig.DEBUG' "$gated"; then
    fail "A debug-only entry point is no longer gated on the build type" \
         "$gated

  Anything that exposes a state the product does not reach on its own must be
  behind BuildConfig.DEBUG, in the file that draws it."
  fi
done

# ------------------------------------------------------ a trap worth failing on
# A no-argument TestDispatcher brings its own TestCoroutineScheduler unless something
# has installed one as Main. Built as a field and handed to production code, every
# suspending test in the class then dies with "detected use of different schedulers"
# -- on CI, minutes after the commit looked fine locally.
#
# Two spellings are perfectly correct and must keep working, so neither is flagged:
#
#   Dispatchers.setMain(StandardTestDispatcher())   -- the ViewModel test pattern;
#                                                      runTest adopts Main's scheduler
#   StandardTestDispatcher(testScheduler)           -- sharing one explicitly
#
# So the check is file-scoped: a no-argument TestDispatcher is only a defect in a file
# that never calls setMain. Both spellings were verified against the real library
# before this rule was written, not assumed.
offenders=""
for file in $(grep -rl --include='*.kt' -E '(Standard|Unconfined)TestDispatcher\(\)' \
                app core feature playback data domain benchmark 2>/dev/null); do
  if ! grep -q 'setMain(' "$file"; then
    offenders="$offenders
$(grep -n -E '(Standard|Unconfined)TestDispatcher\(\)' "$file" | sed "s|^|$file:|")"
  fi
done
if [ -n "$offenders" ]; then
  fail "A TestDispatcher is built with no scheduler and never installed as Main" \
       "$offenders

  Constructed this way it brings its own scheduler, and every suspending test
  in the class fails. Any of these is fine:
    Dispatchers.setMain(StandardTestDispatcher())
    StandardTestDispatcher(testScheduler)
    Dispatchers.Unconfined            -- what the rest of this repository uses"
fi

# ------------------------------------------- a comment that swallows the file
# Kotlin block comments nest. So `/*` written inside a KDoc opens a second comment,
# and the `*/` the author meant to end the KDoc closes only that inner one -- every
# line after it is comment, and the compiler says "Unclosed comment" at the last line
# of the file rather than at the sentence that caused it.
#
# It is written by accident and always the same way: a MIME wildcard in prose. `video/*`
# in a KDoc silently deleted the rest of a screen, and the only thing that found it was
# a seven-minute Android build -- the one gate that cannot run in this sandbox at all.
# Grep finds it in a second, which is the whole argument for the rule being here.
#
# The scan is a small state machine rather than a count of `/*` against `*/`, because a
# string literal is allowed to contain either and a count would fail an innocent file.
# It reports every `/*` seen while already inside a comment; the first is the culprit.
nested=$(awk '
FNR == 1 { depth = 0; raw = 0 }
{
  line = $0; i = 1; n = length(line); str = 0
  while (i <= n) {
    c = substr(line, i, 1); two = substr(line, i, 2); three = substr(line, i, 3)
    if (depth > 0) {
      if (two == "/*") { printf "%s:%d:%s\n", FILENAME, FNR, line; depth++; i += 2; continue }
      if (two == "*/") { depth--; i += 2; continue }
      i++; continue
    }
    if (raw) { if (three == "\"\"\"") { raw = 0; i += 3; continue } i++; continue }
    if (str) { if (c == "\\") { i += 2; continue }
               if (c == "\"") { str = 0 } i++; continue }
    if (three == "\"\"\"") { raw = 1; i += 3; continue }
    if (c == "\"") { str = 1; i++; continue }
    if (c == "'"'"'") { j = i + 1
                  while (j <= n) { cc = substr(line, j, 1)
                                   if (cc == "\\") { j += 2; continue }
                                   if (cc == "'"'"'") break
                                   j++ }
                  i = j + 1; continue }
    if (two == "//") break
    if (two == "/*") { depth++; i += 2; continue }
    i++
  }
}' $(find app core data domain feature playback benchmark -name '*.kt' 2>/dev/null | sort))
if [ -n "$nested" ]; then
  fail "A block comment is opened inside another one" \
       "$nested

  Kotlin nests block comments, so this one does not end where it looks like it
  ends. Write the wildcard in prose, or the example without a comment in it.
  The first line above is the one to fix; the rest are consequences of it."
fi

# ------------------------------------------- test dependencies are per module
# Gradle scopes test dependencies to the module that declares them. A local classpath
# -- an IDE's, or a hand-rolled one -- is usually one flat bag of jars, so a test that
# imports a library its own module never declared compiles perfectly well until the
# build runs it. The failure is always the same shape: green locally, red on CI,
# minutes later.
#
# Only the two libraries this has actually happened with are checked. A list that
# guesses at every dependency would be a list nobody trusts.
declare -A NEEDS=(
  ["kotlinx.coroutines.test"]="libs.coroutines.test"
  ["org.robolectric"]="libs.robolectric"
)
for module in $(find app core data domain feature playback benchmark -name build.gradle.kts 2>/dev/null); do
  dir=$(dirname "$module")
  [ -d "$dir/src/test" ] || continue
  for import in "${!NEEDS[@]}"; do
    alias="${NEEDS[$import]}"
    if grep -rq --include='*.kt' "^import ${import}" "$dir/src/test" 2>/dev/null; then
      if ! grep -q -- "$alias" "$module"; then
        fail "$dir tests import $import without declaring $alias" \
             "$module

  Add: testImplementation($alias)
  It compiles on a wider local classpath and fails the moment Gradle builds it."
      fi
    fi
  done
done

# ------------------------------------------------------------------------ report
if [ "$failures" -eq 0 ]; then
  echo "Design invariants: all mechanical checks pass."
  exit 0
fi

echo "Design invariants: $failures check(s) failed."
echo "The rules and their rationale are in UI_ARCHITECTURE.md section 12."
exit 1
