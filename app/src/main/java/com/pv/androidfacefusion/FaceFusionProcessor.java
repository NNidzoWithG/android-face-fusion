package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main face fusion processor that coordinates face detection, embedding, and swapping
 */
public class FaceFusionProcessor {
    private static final String TAG = "FaceFusionProcessor";
    
    private FaceDetector faceDetector;
    private FaceEmbedder faceEmbedder;
    private FaceSwapper faceSwapper;

    public FaceFusionProcessor(FaceDetector detector, FaceEmbedder embedder, FaceSwapper swapper) {
        this.faceDetector = detector;
        this.faceEmbedder = embedder;
        this.faceSwapper = swapper;
    }

    /**
     * Process face fusion: swap source face into target image
     * Uses proper similarity transformation for alignment
     */
    public Bitmap processFaceFusion(Bitmap sourceImage, Bitmap targetImage) throws Exception {
        return processFaceFusion(sourceImage, targetImage, 0);
    }

    /**
     * Process face fusion for selected target face indices (multi-selection)
     */
    public Bitmap processFaceFusion(
            Bitmap targetImage,
            float[] sourceEmbedding,
            Set<Integer> selectedFaceIndices) throws Exception {

        if (sourceEmbedding == null || sourceEmbedding.length == 0) {
            throw new Exception("Source face embedding is missing");
        }

        if (selectedFaceIndices == null || selectedFaceIndices.isEmpty()) {
            throw new Exception("No target faces selected");
        }

        List<FaceDetector.Face> targetFaces = faceDetector.detectFaces(targetImage);

        if (targetFaces.isEmpty()) {
            throw new Exception("No face detected in target image");
        }

        Bitmap result = targetImage.copy(Bitmap.Config.ARGB_8888, true);

        for (int i = 0; i < targetFaces.size(); i++) {
            if (!selectedFaceIndices.contains(i)) continue;

            FaceDetector.Face targetFace = targetFaces.get(i);
            Bitmap aligned = ImageUtils.alignFace(result, targetFace.landmarks, 128);

            try {
                Bitmap swapped = faceSwapper.swapFace(aligned, sourceEmbedding, result);
                try {
                    Bitmap blended = ImageUtils.blendFaces(
                            result, swapped, targetFace.landmarks, 128
                    );
                    result.recycle();
                    result = blended;
                } finally {
                    swapped.recycle();
                }
            } finally {
                aligned.recycle();
            }
        }

        return result;
    }

    public Bitmap processFaceFusion(Bitmap sourceImage, Bitmap targetImage, Set<Integer> selectedFaceIndices) throws Exception {
        if (selectedFaceIndices == null || selectedFaceIndices.isEmpty()) {
            return processFaceFusionMultiple(sourceImage, targetImage);
        }

        Log.d(TAG, "Starting face fusion process for " + selectedFaceIndices.size() + " selected face(s)...");

        // Detect source face
        List<FaceDetector.Face> sourceFaces = faceDetector.detectFaces(sourceImage);
        if (sourceFaces.isEmpty()) {
            throw new Exception("No face detected in source image. Please use an image with a clear, frontal face.");
        }
        FaceDetector.Face sourceFace = sourceFaces.get(0);

        // Align source face and extract embedding
        Bitmap alignedSourceFace = ImageUtils.alignFace(sourceImage, sourceFace.landmarks, 112);
        float[] sourceEmbedding = faceEmbedder.getEmbedding(alignedSourceFace);

        // Detect target faces
        List<FaceDetector.Face> targetFaces = faceDetector.detectFaces(targetImage);
        if (targetFaces.isEmpty()) {
            throw new Exception("No face detected in target image.");
        }

        Bitmap result = targetImage.copy(Bitmap.Config.ARGB_8888, true);

        for (int i = 0; i < targetFaces.size(); i++) {
            if (!selectedFaceIndices.contains(i)) {
                continue; // Skip faces that are not selected by user
            }

            Log.d(TAG, "Processing selected target face index: " + i);
            FaceDetector.Face targetFace = targetFaces.get(i);

            Bitmap alignedTargetFace = ImageUtils.alignFace(result, targetFace.landmarks, 128);
            Bitmap swappedFace = faceSwapper.swapFace(alignedTargetFace, sourceEmbedding, result);

            Bitmap blended = ImageUtils.blendFaces(result, swappedFace, targetFace.landmarks, 128);
            if (result != targetImage) {
                result.recycle();
            }
            result = blended;

            alignedTargetFace.recycle();
            swappedFace.recycle();
        }

        alignedSourceFace.recycle();
        Log.d(TAG, "Multi-select face fusion completed!");
        return result;
    }

    /**
     * Process face fusion where each target face index is mapped to a specific library face embedding
     */

    public Bitmap processVideoFaceFusion(Bitmap frame, float[] sourceEmbedding) throws Exception {
        List<FaceDetector.Face> faces = faceDetector.detectFaces(frame);

        if (faces.isEmpty()) {
            Log.d(TAG, "No face detected; keeping original video frame");
            return frame;
        }

        FaceDetector.Face largest = faces.get(0);
        float largestArea = 0;

        for (FaceDetector.Face face : faces) {
            float area = face.bbox.width() * face.bbox.height();
            if (area > largestArea) {
                largestArea = area;
                largest = face;
            }
        }

        Log.d(TAG, "Video selected largest face: bbox="
                + largest.bbox + ", score=" + largest.score);

        Bitmap aligned = ImageUtils.alignFace(frame, largest.landmarks, 128);
        Bitmap swapped = null;

        try {
            swapped = faceSwapper.swapFace(aligned, sourceEmbedding, frame);
            return ImageUtils.blendFaces(frame, swapped, largest.landmarks, 128);
        } finally {
            aligned.recycle();
            if (swapped != null) swapped.recycle();
        }
    }

    public Bitmap processFaceFusionWithMapping(Bitmap targetImage, Map<Integer, float[]> targetIndexToEmbeddingMap) throws Exception {
        if (targetIndexToEmbeddingMap == null || targetIndexToEmbeddingMap.isEmpty()) {
            throw new Exception("No target face mappings selected.");
        }

        Log.d(TAG, "Starting mapped face fusion for " + targetIndexToEmbeddingMap.size() + " target face mapping(s)...");

        // Detect target faces
        List<FaceDetector.Face> targetFaces = faceDetector.detectFaces(targetImage);
        if (targetFaces.isEmpty()) {
            throw new Exception("No face detected in target image.");
        }

        Bitmap result = targetImage.copy(Bitmap.Config.ARGB_8888, true);

        for (int i = 0; i < targetFaces.size(); i++) {
            if (!targetIndexToEmbeddingMap.containsKey(i)) {
                continue; // Skip target faces that have no replacement selected
            }

            float[] sourceEmbedding = targetIndexToEmbeddingMap.get(i);
            if (sourceEmbedding == null) continue;

            Log.d(TAG, "Processing target face " + i + " with custom mapped face embedding");
            FaceDetector.Face targetFace = targetFaces.get(i);

            Bitmap alignedTargetFace = ImageUtils.alignFace(result, targetFace.landmarks, 128);
            Bitmap swappedFace = faceSwapper.swapFace(alignedTargetFace, sourceEmbedding, result);

            Bitmap blended = ImageUtils.blendFaces(result, swappedFace, targetFace.landmarks, 128);
            if (result != targetImage) {
                result.recycle();
            }
            result = blended;

            alignedTargetFace.recycle();
            swappedFace.recycle();
        }

        Log.d(TAG, "Mapped face fusion completed!");
        return result;
    }

    /**
     * Process face fusion for a specific target face index (-1 for all faces, or 0..N-1 for single face)
     */
    public Bitmap processFaceFusion(Bitmap sourceImage, Bitmap targetImage, int targetFaceIndex) throws Exception {
        if (targetFaceIndex == -1) {
            return processFaceFusionMultiple(sourceImage, targetImage);
        }

        Log.d(TAG, "Starting face fusion process for target face index: " + targetFaceIndex);
        Log.d(TAG, "Source image size: " + sourceImage.getWidth() + "x" + sourceImage.getHeight());
        Log.d(TAG, "Target image size: " + targetImage.getWidth() + "x" + targetImage.getHeight());
        
        // Step 1: Detect faces in source image
        Log.d(TAG, "Detecting faces in source image...");
        List<FaceDetector.Face> sourceFaces = faceDetector.detectFaces(sourceImage);
        Log.d(TAG, "Found " + sourceFaces.size() + " face(s) in source image");
        
        if (sourceFaces.isEmpty()) {
            throw new Exception("No face detected in source image. Please use an image with a clear, frontal face.");
        }
        
        FaceDetector.Face sourceFace = sourceFaces.get(0); // Use first face
        Log.d(TAG, "Source face bbox: " + sourceFace.bbox.toString() + ", score: " + sourceFace.score);
        
        // Step 2: Detect faces in target image
        Log.d(TAG, "Detecting faces in target image...");
        List<FaceDetector.Face> targetFaces = faceDetector.detectFaces(targetImage);
        Log.d(TAG, "Found " + targetFaces.size() + " face(s) in target image");
        
        if (targetFaces.isEmpty()) {
            String suggestion = "";
            if (targetImage.getWidth() < 300 || targetImage.getHeight() < 300) {
                suggestion = " The target image is quite small (" + targetImage.getWidth() + "x" + 
                           targetImage.getHeight() + "). Try using a larger image with a clearer face.";
            }
            throw new Exception("No face detected in target image. Please use an image with a clear, frontal face." + suggestion);
        }

        if (targetFaceIndex < 0 || targetFaceIndex >= targetFaces.size()) {
            Log.w(TAG, "Target face index " + targetFaceIndex + " out of bounds (found " + targetFaces.size() + " faces), defaulting to 0");
            targetFaceIndex = 0;
        }
        
        FaceDetector.Face targetFace = targetFaces.get(targetFaceIndex);
        Log.d(TAG, "Selected target face " + targetFaceIndex + " bbox: " + targetFace.bbox.toString() + ", score: " + targetFace.score);
        
        // Step 3: Align source face for embedding (112x112 for ArcFace)
        Log.d(TAG, "Aligning source face for recognition...");
        Bitmap alignedSourceFace = ImageUtils.alignFace(sourceImage, sourceFace.landmarks, 112);
        
        // Step 4: Get source face embedding
        Log.d(TAG, "Computing source face embedding...");
        float[] sourceEmbedding = faceEmbedder.getEmbedding(alignedSourceFace);
        
        // Step 5: Align selected target face for swapping (128x128 for INSwapper)
        Log.d(TAG, "Aligning selected target face for swapping...");
        Bitmap alignedTargetFace = ImageUtils.alignFace(targetImage, targetFace.landmarks, 128);
        
        // Step 6: Swap face
        Log.d(TAG, "Swapping face...");
        Bitmap swappedFace = faceSwapper.swapFace(alignedTargetFace, sourceEmbedding, targetImage);
        
        // Step 7: Blend swapped face back using inverse transformation
        Log.d(TAG, "Blending face with inverse transformation...");
        Bitmap result = ImageUtils.blendFaces(targetImage, swappedFace, targetFace.landmarks, 128);
        
        // Cleanup
        alignedSourceFace.recycle();
        alignedTargetFace.recycle();
        swappedFace.recycle();
        
        Log.d(TAG, "Face fusion completed successfully!");
        return result;
    }

    /**
     * Detect faces in target image
     */
    public float[] extractSourceEmbedding(
            Bitmap sourceImage,
            FaceDetector.Face selectedFace) throws Exception {

        if (sourceImage == null || selectedFace == null) {
            throw new Exception("Source image or selected face is missing");
        }

        Bitmap aligned = ImageUtils.alignFace(
                sourceImage, selectedFace.landmarks, 112
        );

        try {
            return faceEmbedder.getEmbedding(aligned);
        } finally {
            aligned.recycle();
        }
    }

    public float[] extractSourceEmbedding(Bitmap sourceImage) throws Exception {
        List<FaceDetector.Face> faces = faceDetector.detectFaces(sourceImage);
        if (faces.isEmpty()) throw new Exception("No source face detected");
        Bitmap aligned = ImageUtils.alignFace(sourceImage, faces.get(0).landmarks, 112);
        try {
            return faceEmbedder.getEmbedding(aligned);
        } finally {
            aligned.recycle();
        }
    }

    public List<FaceDetector.Face> detectTargetFaces(Bitmap targetImage) throws Exception {
        return faceDetector.detectFaces(targetImage);
    }

    /**
     * Process face fusion with multiple target faces
     */
    public Bitmap processFaceFusionMultiple(Bitmap sourceImage, Bitmap targetImage) throws Exception {
        Log.d(TAG, "Starting multiple face fusion process...");
        
        // Detect source face
        List<FaceDetector.Face> sourceFaces = faceDetector.detectFaces(sourceImage);
        if (sourceFaces.isEmpty()) {
            throw new Exception("No face detected in source image");
        }
        FaceDetector.Face sourceFace = sourceFaces.get(0);
        
        // Align source face and extract embedding (no cropping)
        Bitmap alignedSourceFace = ImageUtils.alignFace(sourceImage, sourceFace.landmarks, 112);
        float[] sourceEmbedding = faceEmbedder.getEmbedding(alignedSourceFace);
        
        // Detect all target faces
        List<FaceDetector.Face> targetFaces = faceDetector.detectFaces(targetImage);
        if (targetFaces.isEmpty()) {
            throw new Exception("No face detected in target image");
        }
        
        Bitmap result = targetImage.copy(Bitmap.Config.ARGB_8888, true);
        
        // Swap all detected faces
        for (int i = 0; i < targetFaces.size(); i++) {
            Log.d(TAG, "Processing target face " + (i + 1) + " of " + targetFaces.size());
            FaceDetector.Face targetFace = targetFaces.get(i);
            
            // Align target face for swapping (no cropping)
            Bitmap alignedTargetFace = ImageUtils.alignFace(result, targetFace.landmarks, 128);
            Bitmap swappedFace = faceSwapper.swapFace(alignedTargetFace, sourceEmbedding, result);
            
            // Blend back with inverse transformation
            Bitmap blended = ImageUtils.blendFaces(result, swappedFace, targetFace.landmarks, 128);
            if (result != targetImage) {
                result.recycle();
            }
            result = blended;

            alignedTargetFace.recycle();
            swappedFace.recycle();
        }
        
        alignedSourceFace.recycle();
        
        Log.d(TAG, "Multiple face fusion completed!");
        return result;
    }
}
