package com.yumg.starter.modules.users.application;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class AvatarImageProcessor {
    private static final Set<String> ALLOWED_FORMATS = Set.of("JPEG", "JPG", "PNG", "WEBP");
    private static final int OUTPUT_SIZE = 256;

    private final AvatarCropStrategy cropStrategy;
    private final int maxBytes;
    private final int maxDimension;
    private final long maxPixels;
    private final Semaphore processingSlots;

    @Autowired
    public AvatarImageProcessor(AvatarCropStrategy cropStrategy) {
        this(cropStrategy, 5 * 1024 * 1024, 8192, 40_000_000L, 4);
    }

    AvatarImageProcessor(AvatarCropStrategy cropStrategy, int maxBytes, int maxDimension, long maxPixels) {
        this(cropStrategy, maxBytes, maxDimension, maxPixels, 4);
    }

    AvatarImageProcessor(AvatarCropStrategy cropStrategy, int maxBytes, int maxDimension,
                         long maxPixels, int maxConcurrentProcessing) {
        this.cropStrategy = cropStrategy;
        this.maxBytes = maxBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.processingSlots = new Semaphore(maxConcurrentProcessing);
    }

    public ProcessedAvatar process(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("Avatar image is empty");
        }
        if (source.length > maxBytes) {
            throw new IllegalArgumentException("Avatar image is too large");
        }

        if (!processingSlots.tryAcquire()) {
            throw new IllegalStateException("Avatar processing is busy");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) {
                throw new IllegalArgumentException("Unsupported avatar image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Unsupported avatar image");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new IllegalArgumentException("Unsupported avatar image format");
                }
                rejectAnimatedImage(reader);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage decoded = reader.read(0);
                int orientation = format.equals("JPEG") || format.equals("JPG")
                        ? readExifOrientation(source)
                        : 1;
                BufferedImage oriented = applyOrientation(decoded, orientation);
                return new ProcessedAvatar("image/png", encodePng(cropAndScale(oriented)));
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid avatar image", exception);
        } finally {
            processingSlots.release();
        }
    }

    private void rejectAnimatedImage(ImageReader reader) throws IOException {
        int imageCount = reader.getNumImages(true);
        if (imageCount > 1) {
            throw new IllegalArgumentException("Animated avatar images are not supported");
        }
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > maxDimension || height > maxDimension) {
            throw new IllegalArgumentException("Avatar image dimensions are invalid");
        }
        if ((long) width * height > maxPixels) {
            throw new IllegalArgumentException("Avatar image has too many pixels");
        }
    }

    private int readExifOrientation(byte[] source) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        } catch (Exception ignored) {
            // Malformed or absent EXIF must not prevent decoding an otherwise valid JPEG.
        }
        return 1;
    }

    private BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation == 1) {
            return source;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swapsAxes = orientation >= 5;
        int targetWidth = swapsAxes ? sourceHeight : sourceWidth;
        int targetHeight = swapsAxes ? sourceWidth : sourceHeight;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> { targetX = sourceWidth - 1 - x; targetY = y; }
                    case 3 -> { targetX = sourceWidth - 1 - x; targetY = sourceHeight - 1 - y; }
                    case 4 -> { targetX = x; targetY = sourceHeight - 1 - y; }
                    case 5 -> { targetX = y; targetY = x; }
                    case 6 -> { targetX = sourceHeight - 1 - y; targetY = x; }
                    case 7 -> { targetX = sourceHeight - 1 - y; targetY = sourceWidth - 1 - x; }
                    case 8 -> { targetX = y; targetY = sourceWidth - 1 - x; }
                    default -> { targetX = x; targetY = y; }
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    private BufferedImage cropAndScale(BufferedImage source) {
        Rectangle crop = cropStrategy.crop(source);
        if (crop.width <= 0 || crop.height <= 0 || crop.x < 0 || crop.y < 0
                || crop.x + crop.width > source.getWidth() || crop.y + crop.height > source.getHeight()) {
            throw new IllegalArgumentException("Avatar crop is invalid");
        }
        BufferedImage target = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE,
                    crop.x, crop.y, crop.x + crop.width, crop.y + crop.height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return output.toByteArray();
    }
}
