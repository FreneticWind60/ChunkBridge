# Instrumentation fixtures

The end-to-end tests convert a real world. Player saves are not committed, so this directory is empty in a fresh
checkout and `ConversionEndToEndTest` skips the conversion tests with an `Assume` failure.

To run them locally, copy a world archive here as `test_world.zip` (a zipped Java world folder or a `.mcworld`):

```bash
cp /path/to/world.zip library/src/androidTest/assets/test_world.zip
./gradlew :library:connectedDebugAndroidTest
```

`*.zip` files in this directory are ignored by git on purpose.
