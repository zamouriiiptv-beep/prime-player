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
CastivioTextField'
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
RES=feature/activation/src/main/res
BASE=$RES/values/strings_activation.xml
if [ ! -f "$BASE" ]; then
  fail "The activation strings are missing" "$BASE"
fi

# Every <string> and <plurals> name the default locale declares, minus the ones
# marked untranslatable.
want=$(grep -oE '<(string|plurals) name="[a-z_0-9]+"( translatable="false")?' "$BASE" \
        | grep -v 'translatable="false"' \
        | sed -E 's/.*name="([a-z_0-9]+)".*/\1/' | sort -u)

# Well-formed XML, checked here rather than left to the build.
#
# AAPT rejects these too, but four minutes later and only when someone builds
# the Android modules. It has already cost one red CI run: a generator wrote a
# corrected set of files into the wrong directory and the check standing next to
# it validated the files it had just written rather than the ones in the tree.
# Both were green and the build was not, which is the whole argument for the
# check living where the tree lives.
if command -v python3 >/dev/null 2>&1; then
  bad=$(python3 - "$RES" <<'PYXML'
import glob, sys, xml.etree.ElementTree as ET
for f in sorted(glob.glob(sys.argv[1] + "/values*/*.xml")):
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
for file in "$RES"/values*/strings_activation.xml; do
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

if [ -n "$problems" ]; then
  fail "Activation strings are not complete in every locale" \
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
  if [ ! -f "$RES/$qualifier/strings_activation.xml" ]; then
    fail "A declared language has no strings" \
         "$RES/$qualifier/strings_activation.xml

  CastivioLanguage names this directory, and it does not exist. A language
  in the picker with no resources falls back to English and looks translated."
  fi
done

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
