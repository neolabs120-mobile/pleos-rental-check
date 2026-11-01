import 'package:flutter/widgets.dart';
import 'state/rental_controller.dart';

class RentalScope extends InheritedNotifier<RentalController> {
  const RentalScope({
    super.key,
    required RentalController controller,
    required super.child,
  }) : super(notifier: controller);
  static RentalController of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<RentalScope>()!.notifier!;
}
