package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarImageProcessorTest {
    private final AvatarImageProcessor processor = new AvatarImageProcessor(
            new CenterSquareCropStrategy(), 5 * 1024 * 1024, 8192, 40_000_000L);

    @Test
    void centerCropsAndReencodesPngToA256PixelSquare() throws Exception {
        byte[] source = image("png", 400, 200, true);

        ProcessedAvatar result = processor.process(source);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.png()));

        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(256);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.png()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    }

    @Test
    void acceptsARealStaticWebpImage() throws Exception {
        byte[] source = image("webp", 300, 240, false);

        ProcessedAvatar result = processor.process(source);

        assertThat(ImageIO.read(new ByteArrayInputStream(result.png())).getWidth()).isEqualTo(256);
    }

    @Test
    void rejectsSvgAndOversizedPayloadsBeforeDecode() {
        assertThatThrownBy(() -> processor.process("<svg/>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process(new byte[5 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    private byte[] image(String format, int width, int height, boolean alpha) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.YELLOW);
        graphics.fillRect(width / 4, 0, width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).as("ImageIO writer for %s", format).isTrue();
        return output.toByteArray();
    }
}
