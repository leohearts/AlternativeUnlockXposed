> Warning: This module can NOT protect you from targeted attacks and forensics. You may need more security methods to deal with a complicated threat model.

<p align="center">
<image style="height:200px;display:inline" src="https://github.com/leohearts/AlternativeUnlockXposed/assets/24632029/c34ef40f-6968-418b-8a4a-62667708187b" height="200px" />
<h1 align="center">AlternativeUnlockXposed</h1>
<small><p align="center"><img src='https://img.shields.io/github/v/release/leohearts/AlternativeUnlockXposed'></p></small>
<b><i><p align="center">Unlock your Android phone with an alternative PIN. (Xposed, Root)</p></i></b>
</p>

This app provides an reliable way to run something when providing a specific, wrong PIN on your Android lock screen.

Unlike [Duress](https://play.google.com/store/apps/details?id=me.lucky.duress&hl=en&gl=US), this app uses Xposed Framework so you can also unlock your phone with a wrong PIN, preventing some *social engineering vulunability*😇 And by the way it also works before your first unlock after reboot.

## Feature

- Alternative PIN to unlock phone
- Run command on alternative PIN, with root
- Easy to use user interface
- Material You design

Currently tested on:
- Android 14 (arm64)
- Android 13 (x86_64)

It *should* also work on other architectures .

## HowToUse

- Install Magisk
- Install LSPosed
- Install this module
- Activate this module in LSPosed settings (It automatically enables for SystemUI)
- Launch AlternativeUnlockXposed, allow superuser access, set your primary password and alternative password
- (optional) Setup what to do when entered the alternative PIN: change action to sudo, and set your command.
e.g. : ``for i in `pm list packages | grep -i -E 'telegram|sagernet|twitter|discord|tinder' | cut -d : -f 2` ; do pm disable $i; done``

## Roadmap
- [x] Support PIN unlock
- [x] Run custom command on alternative PIN
- [x] User interface
- [ ] Support more lockscreen modes
- [ ] Add broadcast mode
- [ ] Zygisk version (?)
- [ ] Require authentication for settings activity
- [ ] Option to hide the app from launcher ~~(I don't need it because I use SmartLauncher)~~

## Screenshots

<img width=30% src="https://github.com/leohearts/AlternativeUnlockXposed/assets/24632029/dc4e953e-84d3-44d2-90f8-1fabee249f62">
<img width=30% src="https://github.com/leohearts/AlternativeUnlockXposed/assets/24632029/14464a95-1d45-47fa-8fc1-1b70584adc46">

## How does it work ?

When the fake password is provided, this module detects the input and replaces it with the real password. As a result, both the fake and real passwords can unlock the device, regardless of whether it is the first unlock or not.

Furthermore, since the fake password will be replaced with the real one, the fake password can also successfully decrypt the phone after a reboot.

## Credit

- [Duress](https://play.google.com/store/apps/details?id=me.lucky.duress&hl=en&gl=US) (for this idea)
- Google Bard (for app icon)
