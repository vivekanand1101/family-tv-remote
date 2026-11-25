package com.vivekanand.familytvcompanion;

final class ChannelNumber {
    private ChannelNumber() { }

    static Boolean screenOnFromBeacon(String beacon) {
        if (beacon == null) return null;
        String[] fields = beacon.trim().split(":");
        if (fields.length < 6 || !"beacon".equalsIgnoreCase(fields[0])) return null;
        if ("1".equals(fields[5])) return true;
        if ("0".equals(fields[5])) return false;
        return null;
    }

    static int wakeKeyCode(Boolean screenOn) {
        // KEYCODE_WAKEUP does not restore HDMI output on this JioSTB when its
        // screen is off. KEYCODE_POWER matches the physical remote, but must
        // only be used for a confirmed sleeping box because it is a toggle.
        return Boolean.FALSE.equals(screenOn) ? 26 : 224;
    }

    static int[] tuneKeyCodes(int channelNumber, Boolean screenOn) {
        int[] digits = digitKeyCodes(channelNumber);
        int[] sequence = new int[digits.length + 3];
        sequence[0] = wakeKeyCode(screenOn);
        // The JioSTB does not expose its foreground page. HOME is a global
        // escape from apps, support chats, keyboards, settings, and dialogs;
        // KEYCODE_TV then establishes the only context where digits tune.
        sequence[1] = 3;
        sequence[2] = 170;
        System.arraycopy(digits, 0, sequence, 3, digits.length);
        return sequence;
    }

    static boolean isValid(int channelNumber) {
        return channelNumber > 0 && channelNumber <= 9999;
    }

    static int[] digitKeyCodes(int channelNumber) {
        if (!isValid(channelNumber)) throw new IllegalArgumentException("Channel number must contain one to four digits");
        char[] digits = Integer.toString(channelNumber).toCharArray();
        int[] keyCodes = new int[digits.length];
        for (int index = 0; index < digits.length; index++) {
            keyCodes[index] = 7 + (digits[index] - '0');
        }
        return keyCodes;
    }
}
