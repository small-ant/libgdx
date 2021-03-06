
package com.badlogic.gdx.graphics.g3d.test;

import java.util.Arrays;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL10;
import com.badlogic.gdx.graphics.g3d.AnimatedModelInstance;
import com.badlogic.gdx.graphics.g3d.ModelRenderer;
import com.badlogic.gdx.graphics.g3d.StillModelInstance;
import com.badlogic.gdx.graphics.g3d.experimental.MaterialShaderHandler;
import com.badlogic.gdx.graphics.g3d.lights.LightManager;
import com.badlogic.gdx.graphics.g3d.lights.PointLight;
import com.badlogic.gdx.graphics.g3d.materials.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.materials.Material;
import com.badlogic.gdx.graphics.g3d.materials.MaterialAttribute;
import com.badlogic.gdx.graphics.g3d.materials.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.AnimatedModel;
import com.badlogic.gdx.graphics.g3d.model.Model;
import com.badlogic.gdx.graphics.g3d.model.SubMesh;
import com.badlogic.gdx.graphics.g3d.model.skeleton.SkeletonSubMesh;
import com.badlogic.gdx.graphics.g3d.model.still.StillModel;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

//stuff that happens
//0. render begin
//1. frustum culling
//1.1 if animated, animation is solved and..
//1.2. all models and instances are put to one queue
//3. render ends
//for all models
//5. batching involving shaders, materials and texture should happen.(impossible to do perfect.)
//4. closest lights are calculated per model
//6  models are rendered
//7. tranparency

public class PrototypeRendererGL20 implements ModelRenderer {

	static final int SIZE = 256;// TODO better way
	final private Array<Model> modelQueue = new Array<Model>(false, SIZE);
	final private Array<StillModelInstance> modelInstances = new Array<StillModelInstance>(false, SIZE);

	final private MaterialShaderHandler materialShaderHandler;
	private LightManager lightManager;
	private boolean drawing;
	final private Matrix3 normalMatrix = new Matrix3();
	public Camera cam;

	// TODO maybe there is better way
	public PrototypeRendererGL20 (LightManager lightManager) {
		this.lightManager = lightManager;
		materialShaderHandler = new MaterialShaderHandler(lightManager);
	}

	@Override
	public void begin () {
		drawing = true;
		// all setting has to be done before this
		// example: camera updating or updating lights positions
	}

	@Override
	public void draw (StillModel model, StillModelInstance instance) {
		if (cam != null) if (!cam.frustum.sphereInFrustum(instance.getSortCenter(), instance.getBoundingSphereRadius())) return;
		modelQueue.add(model);
		modelInstances.add(instance);
	}

	@Override
	public void draw (AnimatedModel model, AnimatedModelInstance instance) {
		if (cam != null) if (!cam.frustum.sphereInFrustum(instance.getSortCenter(), instance.getBoundingSphereRadius())) return;
		model.setAnimation(instance.getAnimation(), instance.getAnimationTime(), instance.isLooping());
		// move skinned models to drawing list
		modelQueue.add(model);
		modelInstances.add(instance);
	}

	@Override
	public void end () {
		if (Gdx.graphics.isGL20Available())
			flush();
		else {
			// TODO fixed pipeline
		}
	}

	private ShaderProgram currentShader;
	final private TextureAttribute lastTexture[] = new TextureAttribute[TextureAttribute.MAX_TEXTURE_UNITS];

	private void flush () {
		Material currentMaterial = null;
		// find N nearest lights per model
		// draw all models from opaque queue
		for (int i = 0; i < modelQueue.size; i++) {
			final StillModelInstance instance = modelInstances.get(i);
			final Vector3 center = instance.getSortCenter();
			lightManager.calculateLights(center.x, center.y, center.z);
			final Matrix4 modelMatrix = instance.getTransform();
			normalMatrix.set(modelMatrix);

			final SubMesh subMeshes[] = modelQueue.get(i).getSubMeshes();
			final Material materials[] = instance.getMaterials();

			boolean matrixChanged = true;
			for (int j = 0; j < subMeshes.length; j++) {

				final SubMesh subMesh = subMeshes[j];
				final Material material = materials != null ? materials[j] : subMesh.material;

				if (material.needBlending) {
					addTranparentQueu(material, subMesh, modelMatrix, center);

					continue;
				}

				// bind new shader if material can't use old one
				final boolean shaderChanged = bindShader(material);

				// if shaderChanged can't batch material
				if (shaderChanged) currentMaterial = null;

				// if shaderChanged can't batch material
				if (shaderChanged || matrixChanged) {
					currentShader.setUniformMatrix("u_normalMatrix", normalMatrix, false);
					currentShader.setUniformMatrix("u_modelMatrix", modelMatrix, false);
					matrixChanged = false;
				}
				if ((material != null) && (material != currentMaterial)) {
					currentMaterial = material;
					for (int k = 0, len = currentMaterial.attributes.length; k < len; k++) {
						final MaterialAttribute atrib = currentMaterial.attributes[k];

						// special case for textures. really important to batch these
						if (atrib instanceof TextureAttribute) {
							final TextureAttribute texAtrib = (TextureAttribute)atrib;
							if (!texAtrib.texturePortionEquals(lastTexture[texAtrib.unit])) {
								lastTexture[texAtrib.unit] = texAtrib;
								texAtrib.bind(currentShader);
							} else {
								// need to be done, shader textureAtribute name could be changed.
								currentShader.setUniformi(texAtrib.name, texAtrib.unit);
							}
						} else {
							atrib.bind(currentShader);
						}
					}
				}
				// finally render current submesh
				subMesh.getMesh().render(currentShader, subMesh.primitiveType);
			}
		}
		modelQueue.clear();
		modelInstances.clear();

		// if transparent queue is not empty enable blending(this force gpu to
		// flush and there is some time to sort)
		if (blendQueue.size > 0) renderBlended();

		// cleaning

		if (currentShader != null) {
			currentShader.end();
			currentShader = null;
		}
		for (int i = 0, len = TextureAttribute.MAX_TEXTURE_UNITS; i < len; i++)
			lastTexture[i] = null;
		// clear all queus

		drawing = false;
	}

	/** @param material
	 * @return true if new shader was binded */
	boolean bindShader (Material material) {

		if (material.shader == null) material.shader = materialShaderHandler.getShader(material);

		if (material.shader == currentShader) return false;

		currentShader = material.shader;
		currentShader.begin();

		lightManager.applyGlobalLights(currentShader);
		lightManager.applyLights(currentShader);
		currentShader.setUniformMatrix("u_projectionViewMatrix", cam.combined);
		currentShader.setUniformf("camPos", cam.position.x, cam.position.y, cam.position.z);
		return true;
	}

	public void dispose () {
		materialShaderHandler.dispose();
	}

	class BlendedMesh implements Comparable {
		public static final int PRIORITY_DISCRETE_STEPS = 256;
		public Material material;
		public SubMesh subMesh;
		public Matrix4 modelMatrix;
		public Vector3 center;
		public int distance;

		public BlendedMesh (Material material, SubMesh subMesh, Matrix4 modelMatrix, Vector3 center) {
			this.material = material;
			this.subMesh = subMesh;
			this.modelMatrix = modelMatrix;
			this.center = center;
			distance = (int)(PRIORITY_DISCRETE_STEPS * center.dst(cam.position));
		}

		@Override
		public int compareTo (Object other) {
			return ((BlendedMesh)other).distance - this.distance;
		}
	}

	final private Array<BlendedMesh> blendQueue = new Array<BlendedMesh>(true, 64);

	private void addTranparentQueu (Material material, SubMesh subMesh, Matrix4 modelMatrix, Vector3 center) {
		// FIX ME add pooling
		blendQueue.add(new BlendedMesh(material, subMesh, modelMatrix, center));
	}

	private void renderBlended () {
		Gdx.gl.glEnable(GL10.GL_BLEND);
		Gdx.gl.glDepthMask(false);
		blendQueue.sort();

		Material currentMaterial = null;
		// find N nearest lights per model
		// draw all models from opaque queue

		int lastSrcBlend = -1;
		int lastDstBlend = -1;

		for (int i = 0; i < blendQueue.size; i++) {
			final BlendedMesh instance = blendQueue.get(i);
			final Material material = instance.material;
			lightManager.calculateLights(instance.center.x, instance.center.y, instance.center.z);
			normalMatrix.set(instance.modelMatrix);

			// bind new shader if material can't use old one
			final boolean shaderChanged = bindShader(material);
			// if shaderChanged can't batch material
			if (shaderChanged) currentMaterial = null;

			// if shaderChanged can't batch material
			currentShader.setUniformMatrix("u_normalMatrix", normalMatrix, false);
			currentShader.setUniformMatrix("u_modelMatrix", instance.modelMatrix, false);

			if ((material != null) && (material != currentMaterial)) {
				currentMaterial = material;
				for (int k = 0, len = currentMaterial.attributes.length; k < len; k++) {
					final MaterialAttribute atrib = currentMaterial.attributes[k];

					// yet another instanceof. TODO is there any better way to do this? maybe stuffing this to material
					if (atrib instanceof BlendingAttribute) {
						final BlendingAttribute blending = (BlendingAttribute)atrib;
						if (blending.blendSrcFunc != lastSrcBlend || blending.blendDstFunc != lastDstBlend) {
							atrib.bind(currentShader);
							lastSrcBlend = blending.blendSrcFunc;
							lastDstBlend = blending.blendDstFunc;
						}
					} else if (atrib instanceof TextureAttribute) {
						// special case for textures. really important to batch these
						final TextureAttribute texAtrib = (TextureAttribute)atrib;
						if (!texAtrib.texturePortionEquals(lastTexture[texAtrib.unit])) {
							lastTexture[texAtrib.unit] = texAtrib;
							texAtrib.bind(currentShader);
						} else {
							// need to be done, shader textureAtribute name could be changed.
							currentShader.setUniformi(texAtrib.name, texAtrib.unit);
						}
					} else {
						atrib.bind(currentShader);
					}
				}
			}
			// finally render current submesh
			instance.subMesh.getMesh().render(currentShader, instance.subMesh.primitiveType);
		}

		Gdx.gl.glDepthMask(true);
		Gdx.gl.glDisable(GL10.GL_BLEND);
		blendQueue.clear();
	}
}
