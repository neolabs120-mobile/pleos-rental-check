import 'package:flutter/material.dart';

// Share the vehicle app's deep teal palette so the phone and the car screen read as one product rather than two unrelated themes.
ColorScheme rentalColors(Brightness brightness) {
  final light = brightness == Brightness.light;
  return ColorScheme.fromSeed(
    seedColor: const Color(0xFF006C61),
    brightness: brightness,
  ).copyWith(
    primary: light ? const Color(0xFF006C61) : const Color(0xFF8DDED1),
    onPrimary: light ? Colors.white : const Color(0xFF102D29),
    primaryContainer: light ? const Color(0xFFDDF2EC) : const Color(0xFF193D37),
    onPrimaryContainer: light
        ? const Color(0xFF064C44)
        : const Color(0xFFB8F3E4),
    secondaryContainer: light
        ? const Color(0xFFE6F1ED)
        : const Color(0xFF233832),
    onSecondaryContainer: light
        ? const Color(0xFF1F3F38)
        : const Color(0xFFCEEBDE),
    surface: light ? Colors.white : const Color(0xFF172A26),
    surfaceContainerLow: light
        ? const Color(0xFFF6F9F8)
        : const Color(0xFF1C302B),
    surfaceContainer: light ? const Color(0xFFEFF4F2) : const Color(0xFF213631),
    onSurface: light ? const Color(0xFF162B28) : const Color(0xFFE8F1EE),
    onSurfaceVariant: light ? const Color(0xFF526864) : const Color(0xFFAFC4BE),
    outlineVariant: light ? const Color(0xFFDCE6E2) : const Color(0xFF344A45),
    errorContainer: light ? const Color(0xFFFFF0D6) : const Color(0xFF3A2D1E),
    onErrorContainer: light ? const Color(0xFF634008) : const Color(0xFFFFD194),
  );
}

// Give every screen one type scale, generous touch targets and soft rounded surfaces while keeping Material widgets and their semantics.
ThemeData rentalTheme(Brightness brightness) {
  final colors = rentalColors(brightness);
  final base = ThemeData(useMaterial3: true, colorScheme: colors);
  final text = base.textTheme.copyWith(
    headlineMedium: base.textTheme.headlineMedium?.copyWith(
      fontWeight: FontWeight.w800,
      letterSpacing: -.4,
    ),
    titleLarge: base.textTheme.titleLarge?.copyWith(
      fontWeight: FontWeight.w700,
      letterSpacing: -.2,
    ),
    titleMedium: base.textTheme.titleMedium?.copyWith(
      fontWeight: FontWeight.w700,
    ),
    bodyLarge: base.textTheme.bodyLarge?.copyWith(height: 1.5),
    bodyMedium: base.textTheme.bodyMedium?.copyWith(height: 1.5),
    labelLarge: base.textTheme.labelLarge?.copyWith(
      fontSize: 16,
      fontWeight: FontWeight.w700,
    ),
  );
  final buttonShape = RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(16),
  );
  return base.copyWith(
    textTheme: text,
    scaffoldBackgroundColor: brightness == Brightness.light
        ? const Color(0xFFF1F5F3)
        : const Color(0xFF0F1F1C),
    appBarTheme: AppBarTheme(
      centerTitle: false,
      scrolledUnderElevation: 0,
      backgroundColor: Colors.transparent,
      titleTextStyle: text.titleLarge?.copyWith(
        color: colors.onSurface,
        fontSize: 22,
        fontWeight: FontWeight.w800,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.outlineVariant),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: colors.surface,
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 18),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(16),
        borderSide: BorderSide(color: colors.outlineVariant),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(16),
        borderSide: BorderSide(color: colors.outlineVariant),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(16),
        borderSide: BorderSide(color: colors.primary, width: 2),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, 54),
        textStyle: text.labelLarge,
        shape: buttonShape,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(64, 54),
        textStyle: text.labelLarge,
        side: BorderSide(color: colors.primary.withValues(alpha: .35)),
        shape: buttonShape,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        textStyle: text.labelLarge?.copyWith(fontSize: 15),
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      side: BorderSide.none,
      backgroundColor: colors.secondaryContainer,
      labelStyle: text.labelMedium?.copyWith(
        color: colors.onSecondaryContainer,
        fontWeight: FontWeight.w700,
      ),
      shape: const StadiumBorder(),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: colors.surface,
      indicatorColor: colors.primaryContainer,
      elevation: 0,
      labelTextStyle: WidgetStatePropertyAll(
        text.labelMedium?.copyWith(fontWeight: FontWeight.w700),
      ),
    ),
    dialogTheme: DialogThemeData(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
    dividerTheme: DividerThemeData(color: colors.outlineVariant),
  );
}
