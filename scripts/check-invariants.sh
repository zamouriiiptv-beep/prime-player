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

# The pre-modular screens, written before the design system existed. They are
# replaced by the shell and are to be deleted as each is migrated -- never
# extended. Nothing may be added to this list; that is the whole point of it.
LEGACY='app/src/main/java/com/castivio/tv/ui/WelcomeScreen.kt
app/src/main/java/com/castivio/tv/ui/AddSourceScreens.kt
app/src/main/java/com/castivio/tv/ui/Logo.kt'

not_legacy() {
  # Reads paths on stdin, drops the grandfathered ones.
  grep -v -F -f <(printf '%s\n' "$LEGACY") || true
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
ScreenScaffold ScreenTopBar NowPlayingBadge WatchedTag LogoTile MetaChip'
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

# ------------------------------------------------------------------------ report
if [ "$failures" -eq 0 ]; then
  echo "Design invariants: all mechanical checks pass."
  exit 0
fi

echo "Design invariants: $failures check(s) failed."
echo "The rules and their rationale are in UI_ARCHITECTURE.md section 12."
exit 1
