import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'app_scope.dart';
import 'data/rental_api.dart';
import 'data/session_store.dart';
import 'screens/home_screen.dart';
import 'screens/welcome_screen.dart';
import 'state/rental_controller.dart';
import 'ui/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  const server = String.fromEnvironment(
    'RENTAL_SERVER_URL',
    defaultValue: 'https://rental.invalid',
  );
  final controller = RentalController(
    api: RentalApi(server),
    store: SecureSessionStore(),
  );
  runApp(RentalCheckApp(controller: controller));
  controller.restore();
}

class RentalCheckApp extends StatefulWidget {
  const RentalCheckApp({super.key, required this.controller});
  final RentalController controller;
  @override
  State<RentalCheckApp> createState() => _RentalCheckAppState();
}

class _RentalCheckAppState extends State<RentalCheckApp> {
  final navigator = GlobalKey<NavigatorState>();
  @override
  void initState() {
    super.initState();
    widget.controller.onSessionExpired = () {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        navigator.currentState?.popUntil((r) => r.isFirst);
      });
    };
  }

  ThemeData theme(Brightness brightness) {
    return rentalTheme(brightness);
  }

  @override
  Widget build(BuildContext context) => RentalScope(
    controller: widget.controller,
    child: MaterialApp(
      navigatorKey: navigator,
      title: '렌트체크',
      debugShowCheckedModeBanner: false,
      theme: theme(Brightness.light),
      darkTheme: theme(Brightness.dark),
      locale: const Locale('ko'),
      supportedLocales: const [Locale('ko'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: ListenableBuilder(
        listenable: widget.controller,
        builder: (context, _) {
          if (widget.controller.restoring) {
            return const Scaffold(
              body: Center(child: CircularProgressIndicator()),
            );
          }
          return widget.controller.user == null
              ? const WelcomeScreen()
              : HomeScreen(key: ValueKey(widget.controller.user!.id));
        },
      ),
    ),
  );
}
