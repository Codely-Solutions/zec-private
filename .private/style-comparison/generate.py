"""Temporary review-only adapters over original components; no shipping source edits."""
import re,sys
from pathlib import Path
root,out=map(Path,sys.argv[1:]);out.mkdir(parents=True,exist_ok=True)
design=root/'ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design'

def write(name,text): (out/name).write_text(text)
def replace(text,old,new):
    assert old in text,old
    return text.replace(old,new)

theme=(design/'theme/ZcashTheme.kt').read_text()
theme=replace(theme,'val (baseColors, extendedColors, zashiColors) = themePalettes(useDarkMode, useOledDark)',
    'val standard = LocalStyleComparison.current\n    val (baseColors, extendedColors, zashiColors) = if (standard) comparisonPalettes(useDarkMode) else themePalettes(useDarkMode, useOledDark)')
theme=replace(theme,'LocalZashiTypography provides ZashiTypographyInternal,',
    'LocalZashiTypography provides if (standard) ComparisonSystemTypography else ComparisonOriginalTypography,\n        LocalExtendedTypography provides if (standard) comparisonTypography(LocalExtendedTypography.current) else LocalExtendedTypography.current,')
theme=replace(theme,'import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypographyInternal',
    'import co.electriccoin.zcash.ui.design.theme.typography.ComparisonSystemTypography\nimport co.electriccoin.zcash.ui.design.theme.typography.ComparisonOriginalTypography')
theme=replace(theme,'typography = PrimaryTypography,','typography = if (standard) androidx.compose.material3.Typography() else PrimaryTypography,')
write('ComparisonZcashTheme.kt',theme)

typography=(design/'theme/typography/ZashiTypographyInternal.kt').read_text()
typography=replace(typography,'object ZashiTypographyInternal {','class ZashiTypographyInternal(private val family: FontFamily = InterFontFamily) {')
typography=replace(typography,'fontFamily = InterFontFamily,','fontFamily = family,')
typography+='\nval ComparisonOriginalTypography by lazy { ZashiTypographyInternal() }\nval ComparisonSystemTypography by lazy { ZashiTypographyInternal(FontFamily.Default) }\n'
write('ComparisonZashiTypography.kt',typography)

button=(design/'component/ZashiBigIconButton.kt').read_text()
button=replace(button,'val backgroundModifier =','val standard = co.electriccoin.zcash.ui.design.theme.LocalStyleComparison.current\n    val backgroundModifier = if (standard) Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer) else')
button=replace(button,'shape = RoundedCornerShape(22.dp),','shape = if (standard) androidx.compose.material3.MaterialTheme.shapes.large else RoundedCornerShape(22.dp),')
button=replace(button,'border =\n            BorderStroke','border = if (standard) null else\n            BorderStroke')
button=replace(button,'shadowElevation = shadowElevation,','shadowElevation = if (standard) 0.dp else shadowElevation,')
write('ComparisonBigIconButton.kt',button)

button=(design/'component/ZashiButton.kt').read_text()
button=replace(button,'get() = RoundedCornerShape(12.dp)',
    '@Composable get() = if (co.electriccoin.zcash.ui.design.theme.LocalStyleComparison.current) ButtonDefaults.shape else RoundedCornerShape(12.dp)')
write('ComparisonButton.kt',button)

# Generate only token-to-standard-role aliases. Domain/status colors remain intact.
text=(design/'theme/colors/ZashiColorsInternal.kt').read_text()
classes={name:re.findall(r'val\s+(\w+):\s*(\w+)',body) for name,body in re.findall(r'data class (\w+)\((.*?)\n\)',text,re.S)}
def color(path):
    category,name=path[0],path[-1];low='.'.join(path).lower()
    if category=='Surfaces':
        return {'bgPrimary':'s.background','bgAdjust':'s.background','bgSecondary':'s.surfaceContainerLow','bgTertiary':'s.surfaceContainer','bgQuaternary':'s.surfaceContainerHigh','strokePrimary':'s.outline','strokeSecondary':'s.outlineVariant','divider':'s.outlineVariant','brandBg':'s.primary','brandFg':'s.onPrimary'}.get(name)
    if category=='Text':
        if 'Light' in name:return None
        return 's.error' if 'Error' in name else 's.primary' if 'Link' in name else 's.onSurface.copy(alpha = .38f)' if 'Disabled' in name else 's.onSurface' if 'Primary' in name else 's.onSurfaceVariant'
    if category=='Btns':
        if 'destroy' in low:return None
        if 'disabled' in low:return 's.onSurface.copy(alpha = .12f)' if 'bg' in name.lower() else 's.onSurface.copy(alpha = .38f)'
        if 'border' in name.lower():return 's.outline'
        secondary='secondary' in low
        tertiary=any(x in low for x in ('tertiary','ghost','quaternary'))
        if 'bg' in name.lower():return 'Color.Transparent' if tertiary else 's.secondaryContainer' if secondary else 's.primary'
        return 's.primary' if tertiary else 's.onSecondaryContainer' if secondary else 's.onPrimary'
    if category=='Inputs':
        error='Error' in path[1];disabled='Disabled' in path[1];focused='Focused' in path[1]
        if name in ('bg','bgAlt','asideBg'):return 's.surfaceContainerHigh'
        if name.startswith('stroke'):return 's.error' if error else 's.primary' if focused else 's.outline'
        if name in ('text','textMain'):return 's.onSurface.copy(alpha = .38f)' if disabled else 's.onSurface'
        return 's.error' if error and name in ('label','required') else 's.onSurfaceVariant'
    return None
def mapped(base,typ,path):
    fields=[]
    for key,kind in classes[typ]:
        value=color(path+[key]) if kind=='Color' else mapped(base+'.'+key,kind,path+[key])
        if value:fields.append(key+' = '+value)
    return base+'.copy('+',\n'.join(fields)+')' if fields else None
zashi=[]
for name in ('Surfaces','Text','Btns','Inputs'):
    typ=dict(classes['ZashiColorsInternal'])[name];zashi.append(name+' = '+mapped('base.'+name,typ,[name]))

legacy=(design/'theme/ExtendedColors.kt').read_text()
fields=re.findall(r'val (\w+): Color',legacy)
def legacyrole(n):
    if n in ('historyRedColor','textFieldWarning'):return 's.error'
    if n in ('textPrimary','secondaryColor','textDescriptionDark'):return 's.onSurface'
    if n in ('textDescription','textFieldHint'):return 's.onSurfaceVariant'
    if n=='textDisabled':return 's.onSurface.copy(alpha = .38f)'
    if any(v in n.lower() for v in ('divider','stroke','frame')):return 's.outlineVariant'
    if n in ('reference','complementaryColor','linearProgressBarBackground','circularProgressBarSmall','circularProgressBarScreen'):return 's.primary'
    if n in ('primaryColor','backgroundColor','panelBackgroundColorActive'):return 's.background'
    if any(v in n.lower() for v in ('background','gridcolor')):return 's.surfaceContainerLow'
    return None
extended=[n+' = '+legacyrole(n) for n in fields if legacyrole(n)]
extended+=['topAppBarColors = DefaultTopAppBarColors(s.background, s.onSurface, s.onSurface, s.onSurfaceVariant, s.onSurface)',
           'transparentTopAppBarColors = DefaultTopAppBarColors(Color.Transparent, s.onSurface, s.onSurface, s.onSurfaceVariant, s.onSurface)']
styles=(design/'theme/internal/Typography.kt').read_text()
styleclasses={name:re.findall(r'val\s+(\w+):\s*(\w+)',body) for name,body in re.findall(r'data class (\w+)\((.*?)\n\)',styles,re.S)}
def styled(base,typ):
    return base+'.copy('+',\n'.join(k+' = '+(base+'.'+k+'.copy(fontFamily = FontFamily.'+('Monospace' if 'address' in k.lower() else 'Default')+')' if t=='TextStyle' else styled(base+'.'+k,t)) for k,t in styleclasses[typ])+')'
adapter='''package co.electriccoin.zcash.ui.design.theme
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.*
import co.electriccoin.zcash.ui.design.theme.colors.*
import co.electriccoin.zcash.ui.design.theme.internal.*
val LocalStyleComparison = staticCompositionLocalOf { false }
@Composable fun StyleComparisonScope(standard: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStyleComparison provides standard, content = content)
}
@Composable internal fun comparisonPalettes(dark: Boolean): Triple<ColorScheme, ExtendedColors, ZashiColorsInternal> {
    val context = LocalContext.current
    val s = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    val base = if (dark) DarkZashiColorsInternal else LightZashiColorsInternal
    val old = if (dark) DarkExtendedColorPalette else LightExtendedColorPalette
    return Triple(s, old.copy(EXTENDED), base.copy(ZASHI))
}
internal fun comparisonTypography(value: ExtendedTypography): ExtendedTypography = STYLED
'''.replace('EXTENDED',',\n'.join(extended)).replace('ZASHI',',\n'.join(zashi)).replace('STYLED',styled('value','ExtendedTypography'))
write('ComparisonRoles.kt',adapter)
