QEN
=====
### **Q**uantum **E**ntangled **N**otebook

Built as a prototype for an RFP from someone trying to build their math-tutoring business, this app is intended to allow remote homework-help in fields where the typeset nature of existing collaboration tools like Google Docs would get in the way. The QEN would enable the realtime, remote, and persistent review and markup of handwritten formulae across multiple virtual "pages."

Current Features
-----
- Available in Alpha release on the Play Store, invitation to alpha on request.

  [<img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" width="240px" height="auto">](https://play.google.com/store/apps/details?id=tech.jpco.qen)
- 100% Kotlin, with an emphasis on taking advantage of Kotlin's modern language features
- Custom UI widget, handling encoding and decoding/display of user's on-screen writing
- Fully reactive architecture
  - Powered by RxJava, with Jake Wharton's RxBinding on the front end and an RxJava adapter for Google's Firebase Realtime Database as a remote backend
  - Inspired by MVVM and MVI patterns, including unidirectional data flow
  - Leverages Kotlin and RxJava's functional-programming features, including higher-order functions
- Persistence, creation, erasure, and cycling of multiple pages of handwritten notes, currently in a remote, common pool amongst all users

Future Goals
-----
In very rough priority order:
- Creating per-user page sandboxes and tutor super-users who can interact within other users' sandboxes.
  - Color-code student's vs tutor's markup
- Improving unit and integration testing coverage for existing and future code
- Revise UI per current Material Design guidelines
- Allow localized, as opposed to whole-page, erasure of content
- Seamlessly handle changes to the custom widget's aspect ratio or orientation
  - Utilize a page's as-created aspect ratio to record and display without distortion when said changes occur
  - cache UI-generated rasterization for reuse upon Activity death
- Allow the user to select a specific page, with graphical previews
- Selectable "paper" background: plain, lined, or graph
  - Utilize the device's camera to capture an existing, physical worksheet for use as a background


-----
<small>Google Play and the Google Play logo are trademarks of Google LLC.</small>
