package com.vivekanand.familytvcompanion;

import java.util.Arrays;

public final class ChannelNumberChecks {
    private ChannelNumberChecks() { }

    public static void main(String[] args) {
        check(Arrays.equals(ChannelNumber.digitKeyCodes(509), new int[] {12, 7, 16}), "Aaj Tak digits");
        check(Boolean.TRUE.equals(ChannelNumber.screenOnFromBeacon("beacon:box:1:false:0:1:SV=1")), "awake beacon");
        check(Boolean.FALSE.equals(ChannelNumber.screenOnFromBeacon("beacon:box:1:false:0:0:SV=1")), "sleeping beacon");
        check(ChannelNumber.screenOnFromBeacon("invalid") == null, "unknown beacon state");
        check(ChannelNumber.wakeKeyCode(true) == 224, "awake JioSTB uses non-toggling wake");
        check(ChannelNumber.wakeKeyCode(false) == 26, "sleeping JioSTB uses physical power key");
        check(ChannelNumber.wakeKeyCode(null) == 224, "unknown JioSTB state uses safe wake");
        check(Arrays.equals(ChannelNumber.tuneKeyCodes(509, true), new int[] {224, 3, 170, 12, 7, 16}), "awake normalized tune sequence");
        check(Arrays.equals(ChannelNumber.tuneKeyCodes(509, false), new int[] {26, 3, 170, 12, 7, 16}), "sleeping recovery tune sequence");
        check(Arrays.equals(ChannelNumber.digitKeyCodes(1152), new int[] {8, 8, 12, 9}), "News18 JKLH digits");
        check(Arrays.equals(ChannelNumber.digitKeyCodes(9928), new int[] {16, 16, 9, 15}), "four-digit catalogue channel");
        check(ChannelNumber.isValid(1), "lowest channel is valid");
        check(ChannelNumber.isValid(9999), "highest four-digit channel is valid");
        check(!ChannelNumber.isValid(0), "zero channel is rejected");
        check(!ChannelNumber.isValid(10000), "five-digit channel is rejected");
        for (int keyCode : ChannelNumber.digitKeyCodes(470)) {
            check(keyCode >= 7 && keyCode <= 16, "numeric Android keycode only");
            check(keyCode != 23 && keyCode != 66, "no DPAD_CENTER or Enter");
        }
        System.out.println("PASS: every channel tune resets through Home and Live TV before sending digits");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
