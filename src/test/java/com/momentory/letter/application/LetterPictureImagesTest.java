package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class LetterPictureImagesTest {

    private final LetterPictureImages images = new LetterPictureImages();

    @Test
    void transparentPngBecomesSmallerJpegOfSameSize() throws IOException {
        byte[] png = png(896, 1200);

        byte[] jpeg = images.toJpeg(png).orElseThrow();

        assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(read.getWidth()).isEqualTo(896);
        assertThat(read.getHeight()).isEqualTo(1200);
    }

    @Test
    void unreadableBytesGiveNothing() {
        for (byte[] bad : List.of(new byte[0], "not an image".getBytes())) {
            assertThat(images.toJpeg(bad)).isEmpty();
        }
        assertThat(images.toJpeg(null)).isEmpty();
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
