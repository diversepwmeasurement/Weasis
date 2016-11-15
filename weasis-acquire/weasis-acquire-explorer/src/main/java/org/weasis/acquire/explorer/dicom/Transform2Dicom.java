/*******************************************************************************
 * Copyright (c) 2016 Weasis Team and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.acquire.explorer.dicom;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.io.File;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import javax.media.jai.PlanarImage;
import javax.media.jai.operator.TranslateDescriptor;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.util.UIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.acquire.explorer.AcquireImageInfo;
import org.weasis.acquire.explorer.AcquireManager;
import org.weasis.core.api.image.CropOp;
import org.weasis.core.api.image.RotationOp;
import org.weasis.core.api.image.SimpleOpManager;
import org.weasis.core.api.image.util.ImageFiler;
import org.weasis.core.api.image.util.Unit;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.media.data.Tagable;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TagD.Level;
import org.weasis.dicom.codec.utils.DicomMediaUtils;
import org.weasis.dicom.explorer.pr.PrSerializer;
import org.weasis.dicom.tool.Dicomizer;

public final class Transform2Dicom {

    private static final Logger LOGGER = LoggerFactory.getLogger(Transform2Dicom.class);

    private Transform2Dicom() {
    }

    /**
     * Do the encoding of the given image in a standard lossy JPEG format with optionally doing some pre-processing
     * operations (like resize, flip, crop, zoom, contrast ...) if any postProcessOperation have been set in the
     * AcquireImageInfo. The temporary image file is then encapsulated in a standard DICOM format according to the
     * proper Dicom attributes set in the AcquireImageInfo. This Dicom is written in the exportDirDicom with its
     * sopInstanceUID as filename.
     *
     *
     * @param imageInfo
     * @param exportDirDicom
     * @param exportDirImage
     * @param seriesInstanceUID
     *            Global series for all PR
     * @return
     */

    public static boolean dicomize(AcquireImageInfo imageInfo, File exportDirDicom, File exportDirImage,
        String seriesInstanceUID) {

        ImageElement imageElement = imageInfo.getImage();
        String sopInstanceUID = Objects.requireNonNull((String) imageElement.getTagValue(TagD.getUID(Level.INSTANCE)));

        // Transform to JPEG
        File imgFile = imageElement.getFileCache().getOriginalFile().get();
        if (imgFile == null || !imageElement.getMimeType().contains("jpg") //$NON-NLS-1$
            || !imageInfo.getCurrentValues().equals(imageInfo.getDefaultValues())) {

            imgFile = new File(exportDirImage, sopInstanceUID + ".jpg"); //$NON-NLS-1$
            SimpleOpManager opManager = imageInfo.getPostProcessOpManager();
            PlanarImage transformedImage = imageElement.getImage(opManager, false);
            Rectangle area = (Rectangle) opManager.getParamValue(CropOp.OP_NAME, CropOp.P_AREA);
            Integer rotationAngle = Optional.ofNullable((Integer) opManager.getParamValue(RotationOp.OP_NAME, RotationOp.P_ROTATE)).orElse(0);
            rotationAngle = rotationAngle % 360;
            if (area != null && rotationAngle != 0 && rotationAngle != 180) {
                transformedImage = TranslateDescriptor.create(transformedImage, (float) -area.getX(),
                    (float) -area.getY(), null, null);
            }

            if (!ImageFiler.writeJPG(imgFile, transformedImage, 0.8f)) {
                // out of memory ??
                imgFile.delete();
                LOGGER.error("Cannot Transform to jpeg {}", imageElement.getName()); //$NON-NLS-1$
                return false;
            }
        }

        // Dicomize
        if (imgFile.canRead()) {
            Attributes attrs = imageInfo.getAttributes();
            DicomMediaUtils.fillAttributes(AcquireManager.GLOBAL.getTagEntrySetIterator(), attrs);
            DicomMediaUtils.fillAttributes(imageInfo.getSeries().getTagEntrySetIterator(), attrs);
            DicomMediaUtils.fillAttributes(imageElement.getTagEntrySetIterator(), attrs);
            // Spatial calibration
            if (Unit.PIXEL != imageElement.getPixelSpacingUnit()) {
                attrs.setString(Tag.PixelSpacingCalibrationDescription, VR.LO, "Used fiducial"); //$NON-NLS-1$
                double unitRatio = imageElement.getPixelSize()
                    * Unit.MILLIMETER.getConversionRatio(imageElement.getPixelSpacingUnit().getConvFactor());
                attrs.setDouble(Tag.PixelSpacing, VR.DS, unitRatio, unitRatio);
            }

            try {
                Dicomizer.jpeg(attrs, imgFile, new File(exportDirDicom, sopInstanceUID), false);
            } catch (Exception e) {
                LOGGER.error("Cannot Dicomize {}", imageElement.getName(), e); //$NON-NLS-1$
                return false;
            }

            // Presentation State
            GraphicModel grModel = (GraphicModel) imageElement.getTagValue(TagW.PresentationModel);
            if (grModel != null && grModel.hasSerializableGraphics()) {
                Point2D offset = null;
                Rectangle crop =
                    (Rectangle) imageInfo.getPostProcessOpManager().getParamValue(CropOp.OP_NAME, CropOp.P_AREA);
                if (crop != null) {
                    Integer rotationAngle = Optional.ofNullable((Integer) imageInfo.getPostProcessOpManager().getParamValue(RotationOp.OP_NAME, RotationOp.P_ROTATE)).orElse(0);
                    rotationAngle = rotationAngle % 360;
                    if (rotationAngle == 0 || rotationAngle == 180) {
                        offset = new Point2D.Double(crop.getX(), crop.getY());
                    } else {
                        double factor = 2.0; // work only with 90 and 270 degrees
                        offset = new Point2D.Double(crop.getX() * factor, crop.getY() * factor);
                    }
                }
                String prUid = UIDUtils.createUID();
                File outputFile = new File(exportDirDicom, prUid);
                PrSerializer.writePresentation(grModel, attrs, outputFile, seriesInstanceUID, prUid, offset);
            }
        } else {
            LOGGER.error("Cannot read JPEG image {}", imageElement.getName()); //$NON-NLS-1$
            return false;
        }

        return true;
    }

    /**
     * Populates Date and Time for all Attributes in the imageInfo Collection with respect to the youngest. That is :
     * the first image content Date and Time would define the SerieDate and SerieTime within the current Serie, and so
     * on within the current Study
     *
     * @param collection
     * @param dicomTags
     */
    public static void buildStudySeriesDate(Collection<AcquireImageInfo> collection, final Tagable dicomTags) {

        TagW seriesDate = TagD.get(Tag.SeriesDate);
        TagW seriesTime = TagD.get(Tag.SeriesTime);
        TagW studyDate = TagD.get(Tag.StudyDate);
        TagW studyTime = TagD.get(Tag.StudyTime);

        for (AcquireImageInfo imageInfo : collection) {
            ImageElement imageElement = imageInfo.getImage();
            LocalDateTime date = TagD.dateTime(Tag.ContentDate, Tag.ContentTime, imageElement);
            if (date == null) {
                continue;
            }

            LocalDateTime minSeries = TagD.dateTime(Tag.SeriesDate, Tag.SeriesTime, imageInfo.getSeries());
            if (minSeries == null || date.isBefore(minSeries)) {
                imageInfo.getSeries().setTag(seriesDate, date.toLocalDate());
                imageInfo.getSeries().setTag(seriesTime, date.toLocalTime());
            }

            LocalDateTime minStudy = TagD.dateTime(Tag.StudyDate, Tag.StudyTime, dicomTags);
            if (minStudy == null || date.isBefore(minStudy)) {
                dicomTags.setTag(studyDate, date.toLocalDate());
                dicomTags.setTag(studyTime, date.toLocalTime());
            }
        }
    }
}
