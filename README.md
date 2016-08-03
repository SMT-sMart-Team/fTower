
# cTower: Tower for Chinese cellphones.

Tower is a Ground Control Station (GCS) Android app built atop [3DR Services](https://github.com/dronekit/dronekit-android), for UAVs
running Ardupilot software.

# Tower中国用户适配版本--cTower手机地面站 by offbye

> 3DR官方Tower手机地面站在国内不适用，国内多数手机没有谷歌服务和谷歌市场，因此没法安装3DR services服务，谷歌地图在国内由于众所周知的原因，也不好用。

> 因此我为了自己玩的开心，Fork了Tower项目并逐步改的让国内主流安卓手机能正常使用，希望能够造福魔友，也欢迎有能力的朋友一起开发完善，不欢迎商业闭源使用。

> cTower在尽量保留Tower原版最新功能不变的基础上，针对国内手机用户进行适配和增强，版本号和Tower保持同步。

## 3.2.2 更新说明

 1. cTower地面站，实现支持去掉谷歌gms服务的国产手机
 2. 去掉3DR服务检查 by [i]offbye[/i]
 3. 增加高德地图支持，并设为默认地图，可以设置切换谷歌地图
 4. 修改了应用ID，避免和原版Tower冲突
 5. 修改gradle配置，适配高德地图，解决GFW导致一些repo下载慢无法编译的问题
 6. 增强简体中文翻译

 ### [下载cTower3.2.2版本](http://o79096vir.bkt.clouddn.com/ctower/cTower-v3.2.2-release.apk)
![ctower](http://o79096vir.bkt.clouddn.com/ctower/S60803-20125595.jpg)

下载Tower官方版本 [![Google Play Store](https://developer.android.com/images/brand/en_app_rgb_wo_45.png)](https://play.google.com/store/apps/details?id=org.droidplanner.android)

### Usage Guide

The [wiki](https://github.com/DroidPlanner/droidplanner/wiki) has some basic documentation (Help us by making it better!)

For help, visit the [Tower Discuss forum](http://discuss.ardupilot.org/c/ground-control-software/tower).

### Tower beta

![Droidplanner 3.0 beta screenshot](http://api.ning.com/files/qe5*yho6iFTs6PSl5XqUIDQ9TwN1-tU7Ni93QgCXyWp*zymrcfqPDH4*5ZagwrNcep7*6kr2vgdlccbwytoYqxHwbSDG5yJR/v3.2.1.flight.tablet.land.png)

The next version of Tower is under development, and we would love your help with beta testing. To join the beta:
 1. Access and download the beta through https://play.google.com/store/apps/details?id=org.droidplanner.android.beta.
 2. Post your feedback in the [Tower Discuss forum](http://discuss.ardupilot.org/c/ground-control-software/tower). We want to hear what you think!

#### Requirement
In order to run **Tower** on your device, the latest version of **3DR Services** must be installed.
To do so, follow the instructions on the [3DR Services github page](https://github.com/dronekit/dronekit-android).

### Contributing

Tower is in active development. If you would like to contribute to the project,
see the [Build Setup wiki page](https://github.com/DroidPlanner/Tower/wiki/Build-Setup).

If you aren't a developer but want to help out, you can do so by improving the documentation in the Wiki or by writing user guides.
