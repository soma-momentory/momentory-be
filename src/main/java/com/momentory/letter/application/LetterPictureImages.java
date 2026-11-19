package com.momentory.letter.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Component;

/**
 * 그림일기 저장 형식 — 모델이 준 PNG(약 650KB)를 JPEG(약 150KB)로 줄인다. 새 라이브러리 없이 {@code javax.imageio} 만 쓴다.
 * 공유 · 사진첩 저장용 카드(꾸민 바바 · 한마디)는 앱이 그린다 — 바바 부품 그림과 손글씨 서체가 앱에만 있다.
 */
@Component
public class LetterPictureImages {

    public static final String JPEG = "image/jpeg";

    /** 모델이 준 그림을 JPEG 로 — 읽을 수 없는 바이트면 비어 있다. */
    public Optional<byte[]> toJpeg(byte[] image) {
        if (image == null || image.length == 0) {
            return Optional.empty();
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(img).map(i -> jpeg(rgb(i), 0.85f));
    }

    /** JPEG 에는 투명도가 없다 — 알파가 있는 PNG 는 흰 바탕 위에 옮겨 그린다. */
    private static BufferedImage rgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage img, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("JPEG 로 바꾸지 못했습니다", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
