package com.yumg.starter.modules.users.application;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.springframework.stereotype.Component;

@Component
public class CenterSquareCropStrategy implements AvatarCropStrategy {
    @Override
    public Rectangle crop(BufferedImage image) {
        int side = Math.min(image.getWidth(), image.getHeight());
        return new Rectangle((image.getWidth() - side) / 2, (image.getHeight() - side) / 2, side, side);
    }
}
