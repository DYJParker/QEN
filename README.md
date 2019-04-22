QEN
=====
### **Q**uantum **E**ntangled **N**otebook

Inspired by (and with permission from) an informal RFP from someone trying to build their math-tutoring business, this app is intended to allow remote homework-help in fields where the typeset nature of existing collaboration tools like Google Docs would get in the way. The QEN would enable the realtime, remote review and markup of handwritten formulae across multiple virtual "pages."

Current Features
-----
- 100% Kotlin, with an emphasis on taking advantage of Kotlin's modern language features
- Custom UI widget, handling encoding and decoding/display of user's on-screen writing
- Fully reactive architecture
  - Powered by RxJava, with Jake Wharton's RxBinding on the front end and Square's SQLDelight as a local persistence backend
  - Inspired by MVVM and MVI patterns, including unidirectional data flow
  - Leverages Kotlin and RxJava's functional-programming features, including higher-order functions
- Persistence, creation, erasure, and cycling of multiple pages of handwritten notes

Future Goals
-----
In very rough priority order:
- Allow clients to pair up and share their markup in realtime
  - I've worked with Google's Firebase Realtime Database in the past, so the first approach is to see if its latency is low enough for to use as a middleman
  - Failing that, client-client socket connections, with a custom backend middleman as an ultimate fallback
  - Color-code student's vs tutor's markup
- Provide unit and integration testing coverage for existing and future code
- Allow localized, as opposed to whole-page, erasure of content
- Publish to Google Play Store
- Seamlessly handle changes to the custom widget's aspect ratio or orientation
  - Utilize a page's as-created aspect ratio to record and display without distortion when said changes occur
  - cache UI-generated rasterization for reuse upon Activity death
- Revise UI per current Material Design guidelines
- Allow the user to select a specific page, with graphical previews
- Selectable "paper" background: plain, lined, or graph
  - Utilize the device's camera to capture an existing, physical worksheet for use as a background
