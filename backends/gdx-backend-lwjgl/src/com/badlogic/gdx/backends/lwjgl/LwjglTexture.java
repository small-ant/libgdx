/*******************************************************************************
 * Copyright 2010 Mario Zechner (contact@badlogicgames.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.lwjgl;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.PNGDecoder.Format;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * @author badlogicgames@gmail.com
 * @author Nathan Sweet <misc@n4te.com>
 */
final class LwjglTexture implements Texture {
	/** height in pixels of texture **/
	private int texHeight;
	/** width in pixels of texture **/
	private int texWidth;
	/** whether this textures i managed or not **/
	private final boolean isManaged;
	private int textureID;
	private final boolean isMipMapped;

	/** global number of textures **/
	public static int textures = 0;

	static private ByteBuffer buffer;
	static private IntBuffer intBuffer;
	static private final PNGDecoder pngDecoder = new PNGDecoder();

	LwjglTexture (FileHandle file, TextureFilter minFilter, TextureFilter maxFilter, TextureWrap uWrap, TextureWrap vWrap,
		boolean managed) {
		this.isManaged = managed;
		this.isMipMapped = TextureFilter.isMipMap(minFilter);

		if (!isMipMapped && file.getPath().endsWith(".png")) {
			// Fast path.
			loadPNG(file);
		} else {
			BufferedImage image = (BufferedImage)Gdx.graphics.newPixmap(file).getNativePixmap();
			loadMipMap(image);
		}
		bind();
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, getTextureFilter(minFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, getTextureFilter(maxFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, getTextureWrap(uWrap));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, getTextureWrap(vWrap));
		textures++;
	}

	LwjglTexture (BufferedImage image, TextureFilter minFilter, TextureFilter maxFilter, TextureWrap uWrap, TextureWrap vWrap,
		boolean managed) {
		this.isManaged = managed;
		this.isMipMapped = TextureFilter.isMipMap(minFilter);

		BufferedImage img = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);
		loadMipMap(img);
		this.draw(Gdx.graphics.newPixmap(image), 0, 0);
		bind();
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, getTextureFilter(minFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, getTextureFilter(maxFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, getTextureWrap(uWrap));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, getTextureWrap(vWrap));
		textures++;
	}

	LwjglTexture (int width, int height, int format, TextureFilter minFilter, TextureFilter maxFilter, TextureWrap uWrap,
		TextureWrap vWrap, boolean managed) {
		this.isManaged = managed;
		this.isMipMapped = TextureFilter.isMipMap(minFilter);

		BufferedImage image = new BufferedImage(width, height, format);
		loadMipMap(image);
		bind();
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, getTextureFilter(minFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, getTextureFilter(maxFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, getTextureWrap(uWrap));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, getTextureWrap(vWrap));
		textures++;
	}

	public LwjglTexture (TextureData textureData, TextureFilter minFilter, TextureFilter magFilter, TextureWrap uWrap,
		TextureWrap vWrap) {
		isManaged = false;
		this.isMipMapped = TextureFilter.isMipMap(minFilter);

		textureID = glGenTextures();
		bind();
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, getTextureFilter(minFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, getTextureFilter(magFilter));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, getTextureWrap(uWrap));
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, getTextureWrap(vWrap));

		textureData.load();
		texWidth = textureData.getWidth();
		texHeight = textureData.getHeight();

		textures++;
	}

	private void loadPNG (FileHandle file) {
		try {
			pngDecoder.decodeHeader(file.readFile());
			texWidth = pngDecoder.getWidth();
			texHeight = pngDecoder.getHeight();
			int stride = texWidth * 4;
			int bufferSize = stride * texHeight;
			if (buffer == null || buffer.capacity() < bufferSize)
				buffer = BufferUtils.createByteBuffer(bufferSize);
			else
				buffer.clear();

			Format pngFormat = pngDecoder.decideTextureFormat(PNGDecoder.Format.RGBA);
			int glFormat, glInternalFormat;
			switch (pngFormat) {
			case ALPHA:
				glFormat = GL_ALPHA;
				glInternalFormat = GL_ALPHA8;
				break;
			case LUMINANCE:
				glFormat = GL_LUMINANCE;
				glInternalFormat = GL_LUMINANCE8;
				break;
			case LUMINANCE_ALPHA:
				glFormat = GL_LUMINANCE_ALPHA;
				glInternalFormat = GL_LUMINANCE8_ALPHA8;
				break;
			case RGB:
				glFormat = GL_RGB;
				glInternalFormat = GL_RGB8;
				break;
			case RGBA:
				glFormat = GL_RGBA;
				glInternalFormat = GL_RGBA8;
				break;
			case BGRA:
				glFormat = GL_BGRA;
				glInternalFormat = GL_BGRA;
				break;
			default:
				throw new UnsupportedOperationException("PNG format not handled: " + pngFormat);
			}
			pngDecoder.decode(buffer, stride, pngFormat);
			buffer.flip();

			textureID = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, textureID);
			glTexImage2D(GL_TEXTURE_2D, 0, glInternalFormat, texWidth, texHeight, 0, glFormat, GL_UNSIGNED_BYTE, buffer);
		} catch (IOException ex) {
			throw new GdxRuntimeException("Error loading image file: " + file, ex);
		}
	}

	private ByteBuffer toByteBuffer (BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		int bufferSize = width * height * 4;
		if (buffer == null || buffer.capacity() < bufferSize) {
			buffer = ByteBuffer.allocateDirect(bufferSize);
			ByteBuffer temp = buffer.slice();
			temp.order(ByteOrder.LITTLE_ENDIAN);
			intBuffer = temp.asIntBuffer();
		} else {
			buffer.clear();
			intBuffer.clear();
		}

		Raster raster = image.getRaster();
		if (image.getType() == BufferedImage.TYPE_INT_ARGB)
			intBuffer.put(((DataBufferInt)raster.getDataBuffer()).getData(), 0, width * height);
		else {
			// Same as image.getRGB() without allocating a large int[].
			ColorModel colorModel = image.getColorModel();
			Object data = raster.getDataElements(0, 0, null);
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					intBuffer.put(colorModel.getRGB(raster.getDataElements(x, y, data)));
		}

		buffer.limit(intBuffer.position() * 4);
		return buffer;
	}

	private BufferedImage scaleDown (BufferedImage image) {
		BufferedImage scaled = new BufferedImage(image.getWidth() / 2, image.getHeight() / 2, BufferedImage.TYPE_4BYTE_ABGR_PRE);
		Graphics2D g = scaled.createGraphics();
		g.drawImage(image, 0, 0, scaled.getWidth(), scaled.getHeight(), null); // FIXME replace with something that looks actually
// like a scaled image...
		g.dispose();
		return scaled;
	}

	private void loadMipMap (BufferedImage image) {
		int level = 0;
		int height = image.getHeight();
		int width = image.getWidth();
		texWidth = width;
		texHeight = height;
		textureID = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, textureID);

		while (height >= 1 || width >= 1 && level < 4) {
			ByteBuffer imageBuffer = toByteBuffer(image);
			glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA8, width, height, 0, GL_BGRA, GL_UNSIGNED_BYTE, imageBuffer);
			if (height == 1 || width == 1 || isMipMapped == false) break;

			level++;
			if (height > 1) height /= 2;
			if (width > 1) width /= 2;

			image = scaleDown(image);
		}
	}

	private int getTextureFilter (TextureFilter filter) {
		if (filter == TextureFilter.Linear)
			return GL_LINEAR;
		else if (filter == TextureFilter.Nearest)
			return GL_NEAREST;
		else if (filter == TextureFilter.MipMap)
			return GL_LINEAR_MIPMAP_LINEAR;
		else if (filter == TextureFilter.MipMapNearestNearest)
			return GL_NEAREST_MIPMAP_NEAREST;
		else if (filter == TextureFilter.MipMapNearestLinear)
			return GL_NEAREST_MIPMAP_LINEAR;
		else if (filter == TextureFilter.MipMapLinearNearest)
			return GL_LINEAR_MIPMAP_NEAREST;
		else if (filter == TextureFilter.MipMapLinearLinear)
			return GL_LINEAR_MIPMAP_LINEAR;
		else
			return GL_LINEAR_MIPMAP_LINEAR;
	}

	private int getTextureWrap (TextureWrap wrap) {
		if (wrap == TextureWrap.ClampToEdge)
			return GL_CLAMP;
		else
			return GL_REPEAT;
	}

	public boolean isManaged () {
		return isManaged;
	}

	public void bind () {
		glBindTexture(GL_TEXTURE_2D, textureID);
	}

	public void dispose () {
		glDeleteTextures(textureID);
		textures--;
	}

	public int getHeight () {
		return texHeight;
	}

	public int getWidth () {
		return texWidth;
	}

	public void draw (Pixmap pixmap, int x, int y) {
		if (isManaged) throw new GdxRuntimeException("Can't draw to a managed texture");
		BufferedImage image = (BufferedImage)pixmap.getNativePixmap();

		int level = 0;
		int height = image.getHeight();
		int width = image.getWidth();
		bind();
		while (height >= 1 || width >= 1 && level < 4) {
			ByteBuffer imageBuffer = toByteBuffer(image);
			glTexSubImage2D(GL_TEXTURE_2D, level, x, y, width, height, GL_BGRA, GL_UNSIGNED_BYTE, imageBuffer);
			if (height == 1 || width == 1 || isMipMapped == false) break;

			level++;
			if (height > 1) height /= 2;
			if (width > 1) width /= 2;

			image = scaleDown(image);
		}

	}

	public int getTextureObjectHandle () {
		return textureID;
	}
}
