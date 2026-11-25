#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(CodeplayWifiTvRemotePlugin, "RedmiRemote",
           CAP_PLUGIN_METHOD(getNetworkState, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(openWifiSettings, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(discover, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(pair, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(finishPairing, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(cancelPairing, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(send, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getState, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setKeyboardEnabled, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(sendText, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(replaceText, CAPPluginReturnPromise);
)
