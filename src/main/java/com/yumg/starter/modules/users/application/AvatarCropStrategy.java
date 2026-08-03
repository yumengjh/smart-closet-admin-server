package com.yumg.starter.modules.users.application;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public interface AvatarCropStrategy {
    Rectangle crop(BufferedImage image);
}
