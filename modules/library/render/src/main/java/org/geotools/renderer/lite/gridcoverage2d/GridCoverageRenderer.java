/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2004-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.renderer.lite.gridcoverage2d;

// J2SE dependencies
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImagingOpException;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.InterpolationNearest;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.operator.ConstantDescriptor;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.TypeMap;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.ViewType;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.factory.Hints;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.image.ImageWorker;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.builder.GridToEnvelopeMapper;
import org.geotools.referencing.operation.matrix.XAffineTransform;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.renderer.crs.ProjectionHandler;
import org.geotools.renderer.crs.ProjectionHandlerFinder;
import org.geotools.renderer.crs.WrappingProjectionHandler;
import org.geotools.resources.i18n.ErrorKeys;
import org.geotools.resources.i18n.Errors;
import org.geotools.resources.image.ImageUtilities;
import org.geotools.styling.RasterSymbolizer;
import org.jaitools.imageutils.ImageLayout2;
import org.opengis.coverage.grid.GridCoverage;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.geometry.BoundingBox;
import org.opengis.metadata.spatial.PixelOrientation;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.algorithm.match.HausdorffSimilarityMeasure;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.util.AffineTransformation;

/**
 * A helper class for rendering  {@link GridCoverage}  objects. 
 * @author  Simone Giannecchini, GeoSolutions SAS
 * @author  Andrea Aime, GeoSolutions SAS
 * @author  Alessio Fabiani, GeoSolutions SAS
 *
 *
 * @source $URL$
 * @version  $Id$
 *
 */
@SuppressWarnings("deprecation")
public final class GridCoverageRenderer { 

    private static final double EPS = 1e-6;
  
    /** Logger. */
    private static final Logger LOGGER = org.geotools.util.logging.Logging.getLogger(GridCoverageRenderer.class);
    
    /** IDENTITY */
    private static final AffineTransform IDENTITY = AffineTransform2D.getTranslateInstance(0, 0);

    /**
     * This variable is use for testing purposes in order to force this
     * {@link GridCoverageRenderer} to dump images at various steps on the disk.
     */
    private static boolean DEBUG = Boolean
            .getBoolean("org.geotools.renderer.lite.gridcoverage2d.debug");

    private static String DUMP_DIRECTORY;
    static {
        if (DEBUG) {
            final File tempDir = new File(System.getProperty("user.home"),"gt-renderer");
            if (!tempDir.exists() ) {
                if(!tempDir.mkdir())
                System.out
                        .println("Unable to create debug dir, exiting application!!!");
                DEBUG=false;
                DUMP_DIRECTORY = null;
            } else
               {
                        DUMP_DIRECTORY = tempDir.getAbsolutePath();
                         System.out.println("Rendering debug dir "+DUMP_DIRECTORY);
               }
        }

    }

    /** The Display (User defined) CRS * */
    private final CoordinateReferenceSystem destinationCRS;

    /** Area we want to draw. */
    private final GeneralEnvelope destinationEnvelope;

    /** Size of the area we want to draw in pixels. */
    private final Rectangle destinationSize;

    private final AffineTransform finalGridToWorld;

    private final AffineTransform finalWorldToGrid;

    private Hints hints = new Hints();

    private Interpolation interpolation= new InterpolationNearest();
    
    /** {@link GridCoverageFactory} to be used within this {@link GridCoverageRenderer} instance.*/
    private final GridCoverageFactory gridCoverageFactory;

    private boolean wrapEnabled = true;

    private boolean advancedProjectionHandlingEnabled = true;

    /**
     * Enables/disable map wrapping (active only when rendering off a {@link GridCoverage2DReader}
     * and when advanced projection handling has been enabled too)
     */
    public void setWrapEnabled(boolean wrapEnabled) {
        this.wrapEnabled = wrapEnabled;
    }

    /**
     * Returns true if map wrapping is enabled (active only when rendering off a
     * {@link GridCoverage2DReader} and when advanced projection handling has been enabled too)
     */
    public boolean isWrapEnabled() {
        return this.wrapEnabled;
    }

    /**
     * Enables/disables advanced projection handling (read all areas needed to make up the requested
     * map, cut them to areas where reprojection makes sense, and so on). Works only when rendering
     * off a {@link GridCoverage2DReader}.
     */
    public void setAdvancedProjectionHandlingEnabled(boolean enabled) {
        this.advancedProjectionHandlingEnabled = enabled;
    }

    /**
     * Tests if advanced projection handling is enabled (read all areas needed to make up the
     * requested map, cut them to areas where reprojection makes sense, and so on). Works only when
     * rendering off a {@link GridCoverage2DReader}.
     */
    public boolean isAdvancedProjectionHandlingEnabled() {
        return this.advancedProjectionHandlingEnabled;
    }


    /**
     * Creates a new {@link GridCoverageRenderer} object.
     * 
     * @param destinationCRS
     *                the CRS of the {@link GridCoverage2D} to render.
     * @param envelope
     *                delineating the area to be rendered.
     * @param screenSize
     *                at which we want to rendere the source
     *                {@link GridCoverage2D}.
     * @param worldToScreen if not <code>null</code> and if it contains a rotation,
     * this Affine Tranform is used directly to convert from world coordinates
     * to screen coordinates. Otherwise, a standard {@link GridToEnvelopeMapper}
     * is used to calculate the affine transform.
     * 
     * @throws TransformException
     * @throws NoninvertibleTransformException
     */
    public GridCoverageRenderer(
            final CoordinateReferenceSystem destinationCRS,
            final Envelope envelope, 
            Rectangle screenSize, 
            AffineTransform worldToScreen)
            throws TransformException, NoninvertibleTransformException {

        this(destinationCRS, envelope, screenSize, worldToScreen, null);

    }
    


    /**
     * Creates a new {@link GridCoverageRenderer} object.
     * 
     * @param destinationCRS
     *                the CRS of the {@link GridCoverage2D} to render.
     * @param envelope
     *                delineating the area to be rendered.
     * @param screenSize
     *                at which we want to rendere the source
     *                {@link GridCoverage2D}.
     * @param worldToScreen if not <code>null</code> and if it contains a rotation,
     * this Affine Tranform is used directly to convert from world coordinates
     * to screen coordinates. Otherwise, a standard {@link GridToEnvelopeMapper}
     * is used to calculate the affine transform.
     * 
     * @param newHints
     *                {@link RenderingHints} to control this rendering process.
     * @throws TransformException
     * @throws NoninvertibleTransformException
     */
    public GridCoverageRenderer(
            final CoordinateReferenceSystem destinationCRS,
            final Envelope envelope, 
            final Rectangle screenSize,
            final AffineTransform worldToScreen, 
            final RenderingHints newHints) throws TransformException,
            NoninvertibleTransformException {

        // ///////////////////////////////////////////////////////////////////
        //
        // Initialize this renderer
        //
        // ///////////////////////////////////////////////////////////////////
        this.destinationSize = screenSize;
        this.destinationCRS = destinationCRS;
        if (this.destinationCRS == null) {
            throw new TransformException(Errors.format(ErrorKeys.CANT_SEPARATE_CRS_$1,
                    this.destinationCRS));
        }
        destinationEnvelope = new GeneralEnvelope(new ReferencedEnvelope(envelope,
                this.destinationCRS));
        // ///////////////////////////////////////////////////////////////////
        //
        // FINAL DRAWING DIMENSIONS AND RESOLUTION
        // I am here getting the final drawing dimensions (on the device) and
        // the resolution for this rendererbut in the CRS of the source coverage
        // since I am going to compare this info with the same info for the
        // source coverage.
        //
        // ///////////////////////////////////////////////////////////////////

        // PHUSTAD : The gridToEnvelopeMapper does not handle rotated views.
        //
        if (worldToScreen != null && XAffineTransform.getRotation(worldToScreen) != 0.0) {
            finalWorldToGrid = new AffineTransform(worldToScreen);
            finalGridToWorld = finalWorldToGrid.createInverse();
        } else {
            final GridToEnvelopeMapper gridToEnvelopeMapper = new GridToEnvelopeMapper();
            gridToEnvelopeMapper.setPixelAnchor(PixelInCell.CELL_CORNER);
            gridToEnvelopeMapper.setGridRange(new GridEnvelope2D(destinationSize));
            gridToEnvelopeMapper.setEnvelope(destinationEnvelope);
            finalGridToWorld = new AffineTransform(gridToEnvelopeMapper.createAffineTransform());
            finalWorldToGrid = finalGridToWorld.createInverse();
        }

        //
        // HINTS Management
        //
        if (newHints != null){
            this.hints.add(newHints);
        }
        // factory as needed
        this.gridCoverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints);
        
        // Interpolation
        if(hints.containsKey(JAI.KEY_INTERPOLATION)){
            interpolation = (Interpolation) newHints.get(JAI.KEY_INTERPOLATION);
        }else{
            hints.add(new RenderingHints(JAI.KEY_INTERPOLATION,interpolation));
        }
        if (LOGGER.isLoggable(Level.FINE)){
            LOGGER.fine("Rendering using interpolation "+interpolation);        
        }
        setInterpolationHints();
        
        // Tile Size
        if(hints.containsKey(JAI.KEY_IMAGE_LAYOUT)){
            final ImageLayout layout= (ImageLayout) hints.get(JAI.KEY_IMAGE_LAYOUT);
//            // only tiles are valid at this stage?? TODO
            layout.unsetImageBounds();
            layout.unsetValid(ImageLayout.COLOR_MODEL_MASK&ImageLayout.SAMPLE_MODEL_MASK);
        }
        // this prevents users from overriding lenient hint
        this.hints.put(Hints.LENIENT_DATUM_SHIFT, Boolean.TRUE);
        this.hints.put(Hints.COVERAGE_PROCESSING_VIEW, ViewType.SAME);
        
        //SG add hints for the border extender
        this.hints.add(new RenderingHints(JAI.KEY_BORDER_EXTENDER,BorderExtender.createInstance(BorderExtender.BORDER_COPY)));
    }



    /**
     * 
     */
    private void setInterpolationHints() {
        if(interpolation instanceof InterpolationNearest){
            this.hints.add(new RenderingHints(JAI.KEY_REPLACE_INDEX_COLOR_MODEL, Boolean.FALSE));
            this.hints.add(new RenderingHints(JAI.KEY_TRANSFORM_ON_COLORMAP, Boolean.TRUE));
        } else {
            this.hints.add(new RenderingHints(JAI.KEY_REPLACE_INDEX_COLOR_MODEL, Boolean.TRUE));
            this.hints.add(new RenderingHints(JAI.KEY_TRANSFORM_ON_COLORMAP, Boolean.FALSE));
        }
    }

    /**
     * Write the provided {@link RenderedImage} in the debug directory with the provided file name.
     * 
     * @param raster
     *            the {@link RenderedImage} that we have to write.
     * @param fileName
     *            a {@link String} indicating where we should write it.
     */
    static void writeRenderedImage(final RenderedImage raster, final String fileName) {
        if (DUMP_DIRECTORY == null)
            throw new NullPointerException(
                    "Unable to write the provided coverage in the debug directory");
        if (DEBUG == false)
            throw new IllegalStateException(
                    "Unable to write the provided coverage since we are not in debug mode");
        try {
            ImageIO.write(raster, "tiff", new File(DUMP_DIRECTORY, fileName + ".tiff"));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }
    }

    /**
     * Turns the coverage into a rendered image applying the necessary transformations and the
     * symbolizer
     * 
     * Builds a (RenderedImage, AffineTransform) pair that can be used for rendering onto a
     * {@link Graphics2D} or as the basis to build a final image. Will return null if there is
     * nothing to render.
     * 
     * @param gridCoverage
     * @param symbolizer
     * @return The transformed image, or null if the coverage does not lie within the rendering
     *         bounds
     * @throws Exception
     */
    public RenderedImage renderImage(
            final GridCoverage2D gridCoverage,
            final RasterSymbolizer symbolizer,
            final double[] bkgValues )throws Exception {

        final GridCoverage2D symbolizerGC = renderCoverage(gridCoverage, symbolizer, bkgValues);
        return symbolizerGC.getRenderedImage();
    }



    private GridCoverage2D renderCoverage(final GridCoverage2D gridCoverage,
            final RasterSymbolizer symbolizer, final double[] bkgValues) throws FactoryException {
        // Initial checks
        GridCoverageRendererUtilities.ensureNotNull(gridCoverage, "gridCoverage");    	
        if (LOGGER.isLoggable(Level.FINE)){
            LOGGER.fine("Drawing coverage "+gridCoverage.toString());
        }

        // //
        //
        // REPROJECTION NEEDED?
        //
        // //
        boolean doReprojection =false; 
        final CoordinateReferenceSystem coverageCRS = gridCoverage.getCoordinateReferenceSystem2D();
        if(!CRS.equalsIgnoreMetadata(coverageCRS, destinationCRS)){
            final MathTransform transform = CRS.findMathTransform(coverageCRS, destinationCRS, true);
            doReprojection = !transform.isIdentity();
            if (LOGGER.isLoggable(Level.FINE)){
                LOGGER.fine("Reproject needed for rendering provided coverage");
            }    
        }

        // /////////////////////////////////////////////////////////////////////
        //
        // CROP
        //
        // /////////////////////////////////////////////////////////////////////
        final GridCoverage2D preReprojection = crop(gridCoverage, destinationEnvelope,
                doReprojection);
        if (preReprojection == null) {
            // nothing to render, the AOI does not overlap
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Skipping current coverage because crop failed");
            }
            return null;
        }
        
        // /////////////////////////////////////////////////////////////////////
        //
        // REPROJECTION if needed
        //
        // /////////////////////////////////////////////////////////////////////
        final GridCoverage2D afterReprojection=reproject(preReprojection, doReprojection,bkgValues);
        
        // symbolizer
        return symbolize(afterReprojection, symbolizer, bkgValues);
    }

    private GridCoverage2D symbolize(final GridCoverage2D coverage,
            final RasterSymbolizer symbolizer, final double[] bkgValues) {
        // ///////////////////////////////////////////////////////////////////
        //
        // FINAL AFFINE
        //
        // ///////////////////////////////////////////////////////////////////
        final GridCoverage2D preSymbolizer = affine(coverage, bkgValues);
        if (preSymbolizer == null) {
            return null;
        }
        
        // ///////////////////////////////////////////////////////////////////
        //
        // RASTERSYMBOLIZER
        //
        // ///////////////////////////////////////////////////////////////////
        final GridCoverage2D symbolizerGC;
        if(symbolizer!=null){
                if (LOGGER.isLoggable(Level.FINE)){
                    LOGGER.fine("Applying Raster Symbolizer ");
                }            
        	final RasterSymbolizerHelper rsp = new RasterSymbolizerHelper (preSymbolizer,this.hints);
        	rsp.visit(symbolizer);
        	symbolizerGC = (GridCoverage2D) rsp.getOutput();
    	} else {
            symbolizerGC = preSymbolizer;
        }
        if (DEBUG) {
            writeRenderedImage( symbolizerGC.geophysics(false).getRenderedImage(),"postSymbolizer");
        }
        return symbolizerGC;
    }            	

    /**
     * @param preResample
     * @param doReprojection 
     * @param bkgValues 
     * @return
     * @throws FactoryException 
     */
    private GridCoverage2D reproject(GridCoverage2D preResample, boolean doReprojection, double[] bkgValues) throws FactoryException {
        GridCoverage2D afterReprojection=null;
        try{
            if (doReprojection) {
                afterReprojection = GridCoverageRendererUtilities.reproject(
                        preResample, 
                        destinationCRS,
                        interpolation, 
                        destinationEnvelope,
                        bkgValues,
                        hints);
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.fine("Reprojecting to crs " + destinationCRS.toString());
            } else{
                afterReprojection = preResample;
            }
            return afterReprojection;
        }finally{
            if (DEBUG) {
                if(afterReprojection!=null){
                    writeRenderedImage(
                            afterReprojection.geophysics(false).getRenderedImage(),
                            "afterReprojection");
                }
            }
        }
    }



    /**
     * @param destinationEnvelope 
     * @param gridCoverage
     * @return
     */
    private GridCoverage2D crop(
            final GridCoverage2D inputCoverage, 
            final GeneralEnvelope destinationEnvelope,
            final boolean doReprojection) {
        
        // //
        //
        // CREATING THE CROP ENVELOPE
        //
        // //  
        GridCoverage2D outputCoverage=inputCoverage;
        final GeneralEnvelope coverageEnvelope = (GeneralEnvelope) inputCoverage.getEnvelope();       
        final CoordinateReferenceSystem coverageCRS = inputCoverage.getCoordinateReferenceSystem2D();

        try{
            GeneralEnvelope renderingEnvelopeInCoverageCRS=null; 
            if(doReprojection){
                renderingEnvelopeInCoverageCRS = GridCoverageRendererUtilities
                        .reprojectEnvelopeWithWGS84Pivot(destinationEnvelope, coverageCRS);
            }else{
                // NO REPROJECTION
                renderingEnvelopeInCoverageCRS = new GeneralEnvelope(destinationEnvelope);
            }
            final GeneralEnvelope cropEnvelope = new GeneralEnvelope(renderingEnvelopeInCoverageCRS);
            cropEnvelope.intersect(coverageEnvelope);
            if (cropEnvelope.isEmpty()||cropEnvelope.isNull()) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("The destination envelope does not intersect the envelope of the source coverage.");
                }
                return null;
            }   
            
            ////
            //
            // Cropping for real
            //
            /////
            outputCoverage = GridCoverageRendererUtilities.crop(inputCoverage, cropEnvelope, hints);
        }catch (Throwable t) {
                ////
                //
                // If it happens that the crop fails we try to proceed since the crop does only an optimization. Things might
                // work out anyway.
                //
                ////{
            if (LOGGER.isLoggable(Level.FINE)){
                LOGGER.log(Level.FINE,"Crop Failed for reason: "+t.getLocalizedMessage(),t);
            }
            outputCoverage=inputCoverage;
        }

        if (DEBUG) {
            writeRenderedImage(
                    outputCoverage.geophysics(false).getRenderedImage(),
                    "crop");
        }
        return outputCoverage;
        
    }



    /**
     * @param bkgValues 
     * @param preResample
     * @return
     */
    private GridCoverage2D affine(GridCoverage2D input, double[] bkgValues) {
     // NOTICE that at this stage the image we get should be 8 bits, either RGB, RGBA, Gray, GrayA
        // either multiband or indexed. It could also be 16 bits indexed!!!!
        final RenderedImage finalImage = input.getRenderedImage();
        final GridGeometry2D preSymbolizerGridGeometry = (input.getGridGeometry());
        // I need to translate half of a pixel since in wms the envelope
        // map to the corners of the raster space not to the center of the
        // pixels.
        final MathTransform2D finalGCTransform=preSymbolizerGridGeometry.getGridToCRS2D(PixelOrientation.UPPER_LEFT);
        if (!(finalGCTransform instanceof AffineTransform)) {
            throw new UnsupportedOperationException(
                    "Non-affine transformations not yet implemented"); // TODO
        }
        final AffineTransform finalGCgridToWorld = new AffineTransform((AffineTransform) finalGCTransform);


        // //
        //
        // I am going to concatenate the final world to grid transform for the
        // screen area with the grid to world transform of the input coverage.
        //
        // This way i right away position the coverage at the right place in the
        // area of interest for the device.
        //
        // //
        final AffineTransform finalRasterTransformation = (AffineTransform) finalWorldToGrid.clone();
        finalRasterTransformation.concatenate(finalGCgridToWorld);
       
        //paranoiac check to avoid that JAI freaks out when computing its internal layouT on images that are too small
        Rectangle2D finalLayout= GridCoverageRendererUtilities.layoutHelper(
                        finalImage, 
                        (float)finalRasterTransformation.getScaleX(), 
                        (float)finalRasterTransformation.getScaleY(), 
                        (float)finalRasterTransformation.getTranslateX(), 
                        (float)finalRasterTransformation.getTranslateY(), 
                        interpolation);
        if(finalLayout.isEmpty()){
                if(LOGGER.isLoggable(java.util.logging.Level.FINE))
                        LOGGER.fine("Unable to create a granuleDescriptor "+this.toString()+ " due to jai scale bug");
                return null;
        }

        RenderedImage im=null;
        try {
            ImageWorker iw = new ImageWorker(finalImage);
            iw.setRenderingHints(hints);
            iw.affine(finalRasterTransformation, interpolation, bkgValues);
            im = iw.getRenderedImage();
        } finally {
                if(DEBUG){
                    writeRenderedImage(im, "postAffine");
                }
        }        
        // recreate gridCoverage
        int numBands = im.getSampleModel().getNumBands();
        GridSampleDimension[] sd = new GridSampleDimension[numBands];
        for(int i=0;i<numBands;i++) {
            sd[i]= new GridSampleDimension(TypeMap.getColorInterpretation(im.getColorModel(), i).name());
        }
        
        // create a new grid coverage but preserve as much input as possible
       return this.gridCoverageFactory.create(
               input.getName(), 
                im,
                new GridGeometry2D(
                        new GridEnvelope2D(PlanarImage.wrapRenderedImage(im).getBounds()), 
                        input.getEnvelope()), 
                sd, 
                new GridCoverage[] { input },
                input.getProperties());    
    }



    /**
     * Turns the coverage into a rendered image applying the necessary transformations and the
     * symbolizer
     * 
     * @param gridCoverage
     * @param symbolizer
     * @return The transformed image, or null if the coverage does not lie within the rendering
     *         bounds
     * @throws FactoryException
     * @throws TransformException
     * @throws NoninvertibleTransformException
     * @Deprecated
     */
    public RenderedImage renderImage(
            final GridCoverage2D gridCoverage,
            final RasterSymbolizer symbolizer, 
            final Interpolation interpolation, 
            final Color background,
            final int tileSizeX, 
            final int tileSizeY
            ) throws FactoryException, TransformException, NoninvertibleTransformException {

        GridCoverage2D coverage = renderCoverage(gridCoverage, symbolizer, interpolation,
                background, tileSizeX,
                tileSizeY);
        return coverage.getRenderedImage();
    }

    private GridCoverage2D renderCoverage(final GridCoverage2D gridCoverage,
            final RasterSymbolizer symbolizer, final Interpolation interpolation,
            final Color background, final int tileSizeX, final int tileSizeY)
            throws FactoryException {
        final Hints oldHints= this.hints.clone();
        
        setupTilingHints(tileSizeX, tileSizeY);
        setupInterpolationHints(interpolation);
 
        try {
            return renderCoverage(gridCoverage, symbolizer,
                    GridCoverageRendererUtilities.colorToArray(background));
        } catch (Exception e) {
            throw new FactoryException(e);
        } finally {
            this.hints = oldHints;
        }
    }



    private void setupTilingHints(final int tileSizeX, final int tileSizeY) {
        ////
        //
        // TILING
        //
        //// 
        if(tileSizeX>0&&tileSizeY>0){
            // Tile Size
            final ImageLayout layout= new ImageLayout2();
            layout.setTileGridXOffset(0).setTileGridYOffset(0).setTileHeight(tileSizeY).setTileWidth(tileSizeX);
            hints.add(new RenderingHints(JAI.KEY_IMAGE_LAYOUT,layout));            
        }
    }



    private void setupInterpolationHints(final Interpolation interpolation) {
        ////
        //
        // INTERPOLATION
        //
        ////
        if(interpolation!=null){
            if (LOGGER.isLoggable(Level.FINE)){
                LOGGER.fine("Rendering using interpolation "+interpolation);        
            }
            this.interpolation=interpolation;
            hints.add(new RenderingHints(JAI.KEY_INTERPOLATION,this.interpolation));
            if (LOGGER.isLoggable(Level.FINE)){
                LOGGER.fine("Rendering using interpolation "+interpolation);        
            }
            setInterpolationHints();
        }
    }
    
    public RenderedImage renderImage(final GridCoverage2DReader reader,
            GeneralParameterValue[] readParams, final RasterSymbolizer symbolizer, final Interpolation interpolation,
            final Color background, final int tileSizeX, final int tileSizeY) throws FactoryException, TransformException,
            NoninvertibleTransformException, IOException {
        // setup the hints
        setupTilingHints(tileSizeX, tileSizeY);
        setupInterpolationHints(interpolation);

        return renderImage(reader, readParams, symbolizer, interpolation, background);
    }

    private RenderedImage renderImage(final GridCoverage2DReader reader,
            GeneralParameterValue[] readParams, final RasterSymbolizer symbolizer,
            final Interpolation interpolation, final Color background) throws FactoryException,
            IOException, TransformException {
        // see if we have a projection handler
        CoordinateReferenceSystem sourceCRS = reader.getCoordinateReferenceSystem();
        CoordinateReferenceSystem targetCRS = destinationEnvelope.getCoordinateReferenceSystem();

        ProjectionHandler handler = null;
        List<GridCoverage2D> coverages;
        // read all the coverages we need, cut and whatnot
        GridCoverageReaderHelper rh = new GridCoverageReaderHelper(reader, destinationSize,
                ReferencedEnvelope.reference(destinationEnvelope), interpolation);
        // are we dealing with a remote service wrapped in a reader, one that can handle reprojection
        // by itself?
        if(GridCoverageReaderHelper.isReprojectingReader(reader)) {
            GridCoverage2D coverage = rh.readCoverage(readParams);
            coverages = new ArrayList<>();
            coverages.add(coverage);
        } else {
            if (advancedProjectionHandlingEnabled) {
                handler = ProjectionHandlerFinder.getHandler(rh.getReadEnvelope(), sourceCRS,
                        wrapEnabled);
                if (handler instanceof WrappingProjectionHandler) {
                    // raster data is monolithic and can cover the whole world, disable
                    // the geometry wrapping heuristic
                    ((WrappingProjectionHandler) handler).setDatelineWrappingCheckEnabled(false);
                }
            }
            coverages = rh.readCoverages(readParams, handler);
        }
        /////////////////////////////////////////////////////
        //
        // Check if Mosaiking and reprojection must be done,
        // if so we must add an alpha band in order to avoid 
        // mosaiking issues
        //
        /////////////////////////////////////////////////////
        List<GridCoverage2D> alphaCoverages = new ArrayList<GridCoverage2D>();
        if (coverages.size() > 1) {
            boolean reprojectionNeeded = false;
            for (GridCoverage2D coverage : coverages) {
                if (coverage == null) {
                    continue;
                }
                final CoordinateReferenceSystem coverageCRS = coverage
                        .getCoordinateReferenceSystem();
                if (!CRS.equalsIgnoreMetadata(coverageCRS, destinationCRS)) {
                    reprojectionNeeded = true;
                    break;
                }
            }
            // TODO OPTIMIZE BY CHECKING IF THE REPROJECTION ADD ROTATION ELEMENTS
            if (reprojectionNeeded) {
                // Apply the alpha band
                for (GridCoverage2D coverage : coverages) {
                    if (coverage == null) {
                        continue;
                    }
                    // Apply the alpha band
                    GridCoverage2D alphaCoverage = createAlphaBand(coverage);
                    // Add to the list
                    alphaCoverages.add(alphaCoverage);
                }
            }
        }

        // reproject if needed
        double[] bgValues = GridCoverageRendererUtilities.colorToArray(background);
        List<GridCoverage2D> reprojectedCoverages = new ArrayList<GridCoverage2D>();
        List<GridCoverage2D> reprojectedAlphas = new ArrayList<GridCoverage2D>();
        boolean alphaAdded = alphaCoverages.size() > 1;
        // Index value for the alpha bands
        int index = 0;
        for (GridCoverage2D coverage : coverages) {
            GridCoverage2D alpha = null;
            // If alpha channel has been generated, we use it and reproject it
            if (alphaAdded) {
                alpha = alphaCoverages.get(index);
                index++;
            }
            if (coverage == null) {
                continue;
            }
            final CoordinateReferenceSystem coverageCRS = coverage.getCoordinateReferenceSystem();
            if (!CRS.equalsIgnoreMetadata(coverageCRS, destinationCRS)) {
                GridCoverage2D reprojected = reproject(coverage, true, bgValues);
                GridCoverage2D reprojectedAlpha = alpha != null ? reproject(alpha, true, new double[1]) : null;
                if (reprojected != null) {
                    reprojectedCoverages.add(reprojected);
                }
                // Reprojection of the alpha band
                if (reprojectedAlpha != null) {
                    reprojectedAlphas.add(reprojectedAlpha);
                }
            } else {
                reprojectedCoverages.addAll(coverages);
            }
        }

        // displace them if needed via a projection handler
        List<GridCoverage2D> displacedCoverages = new ArrayList<GridCoverage2D>();
        List<GridCoverage2D> displacedAlphas = new ArrayList<GridCoverage2D>();
        if (handler != null) {
            Envelope testEnvelope = ReferencedEnvelope.reference(destinationEnvelope);
            MathTransform mt = CRS.findMathTransform(sourceCRS, targetCRS);
            PolygonExtractor polygonExtractor = new PolygonExtractor();
            int i = 0;
            for (GridCoverage2D coverage : reprojectedCoverages) {
                // Check on the alpha band
                GridCoverage2D alpha = null;
                if (alphaAdded) {
                    alpha = reprojectedAlphas.get(i);
                }
                Polygon polygon = JTS.toGeometry((BoundingBox) coverage.getEnvelope2D());
                Geometry postProcessed = handler.postProcess(mt, polygon);
                // extract sub-polygons and displace
                List<Polygon> polygons = polygonExtractor.getPolygons(postProcessed);
                for (Polygon displaced : polygons) {
                    // check we are really inside the display area before moving one
                    Envelope intersection = testEnvelope.intersection(displaced
                            .getEnvelopeInternal());
                    if (intersection == null || intersection.isNull()
                            || intersection.getArea() == 0) {
                        continue;
                    }
                    if (displaced.equals(polygon)) {
                        displacedCoverages.add(coverage);
                        // Alpha band reprojection
                        if (alpha != null) {
                            displacedAlphas.add(alpha);
                        }
                    } else {
                        double[] tx = getTranslationFactors(polygon, displaced);
                        if (tx != null) {
                            GridCoverage2D displacedCoverage = displaceCoverage(coverage, tx[0],
                                    tx[1]);
                            displacedCoverages.add(displacedCoverage);
                            // Alpha band displacement
                            if (alpha != null) {
                                GridCoverage2D displacedAlpha = displaceCoverage(alpha, tx[0],
                                        tx[1]);
                                displacedAlphas.add(displacedAlpha);
                            }
                        }
                    }
                }
                i++;
            }
        } else {
            displacedAlphas.addAll(reprojectedAlphas);
            displacedCoverages.addAll(reprojectedCoverages);
        }
        
        // symbolize each bit (done here to make sure we can perform the warp/affine reduction)
        List<GridCoverage2D> symbolizedCoverages = new ArrayList<>();
        List<GridCoverage2D> affineAlphas = new ArrayList<>();
        int ii = 0;
        for (GridCoverage2D displaced : displacedCoverages) {
            GridCoverage2D symbolized = symbolize(displaced, symbolizer,
                    bgValues);
            // Applying affine operation
            if (alphaAdded) {
                GridCoverage2D alpha = displacedAlphas.get(ii);
                GridCoverage2D affineAlpha = affine(alpha, new double[1]);
                affineAlphas.add(affineAlpha);
            } else {
                affineAlphas.addAll(displacedAlphas);
            }
            symbolizedCoverages.add(symbolized);
            ii++;
        }

        // Parameters used for taking into account an optional removal of the alpha band 
        // and an optional reindexing after color expansion
        boolean indexed = false;

        // if more than one coverage, mosaic
        GridCoverage2D mosaicked = null;
        if (symbolizedCoverages.size() == 0) {
            return null;
        } else if (symbolizedCoverages.size() == 1) {
            mosaicked = symbolizedCoverages.get(0);
        } else {
            // Add alpha band if needed
            List<GridCoverage2D> expandedCoverages = new ArrayList<GridCoverage2D>();
            List<GridCoverage2D> alphas = new ArrayList<GridCoverage2D>();
            // Expand the dataset if needed
            if (alphaAdded) {
                // Getting the first coverage
                GridCoverage2D s0 = symbolizedCoverages.get(0);
                // Getting its renderedImage
                ImageWorker w = new ImageWorker(s0.getRenderedImage());
                // Check indexed
                indexed = w.isIndexed();
                List<GridCoverage2D> expandedCovs = new ArrayList<GridCoverage2D>();
                if (indexed) {
                    // Expand GridCoverage ColorModel
                    for (GridCoverage2D cov : symbolizedCoverages) {
                        RenderedImage img = cov.getRenderedImage();
                        ImageWorker worker = new ImageWorker(img);
                        worker.forceComponentColorModel();
                        GridCoverage2D expanded = gridCoverageFactory.create(cov.getName(),
                                worker.getRenderedImage(), cov.getGridGeometry(), null,
                                new GridCoverage2D[] { cov }, cov.getProperties());
                        expandedCovs.add(expanded);
                    }
                } else {
                    // Simply copy the data
                    expandedCovs.addAll(symbolizedCoverages);
                }
                // Add the coverages to the main coverage list
                expandedCoverages.addAll(expandedCovs);
                // Add alpha bands
                alphas.addAll(affineAlphas);
            } else {
                expandedCoverages.addAll(symbolizedCoverages);
            }
            // Mosaic input data
            mosaicked = GridCoverageRendererUtilities.mosaic(expandedCoverages, alphas,
                    destinationEnvelope, hints, bgValues);
        }

        // the mosaicking can cut off images that are just slightly out of the
        // request (effect of the read buffer + a request touching the actual data area)
        if (mosaicked == null) {
            return null;
        }

        // If the image was indexed with must return to the original colormodel
        if(indexed){
            // Getting the coverage renderedImage
            RenderedImage result = mosaicked.getRenderedImage();
            ImageWorker w = new ImageWorker(result);
            w.forceIndexColorModel(true);
            result = w.getRenderedImage();
            // Create the GridCoverage
            GridCoverage2D newCoverage = gridCoverageFactory.create(mosaicked.getName(),
                    result, mosaicked.getGridGeometry(), null,
                    new GridCoverage2D[] { mosaicked }, mosaicked.getProperties());
            mosaicked = newCoverage;
        }

        // at this point, we might have a coverage that's still slightly larger
        // than the one requested, crop as needed
        GridCoverage2D cropped = crop(mosaicked, destinationEnvelope, false);
        if (cropped == null) {
            return null;
        }

        return cropped.getRenderedImage();

    }

    /**
     * Method for creating an alpha band to use for mosaiking
     * 
     * @param coverage
     * @return a new GridCoverage containing a single band with the same size of the input Coverage
     */
    private GridCoverage2D createAlphaBand(GridCoverage2D coverage) {
        // Getting input image
        RenderedImage input = coverage.getRenderedImage();
        // Define image layout
        ImageLayout layout = new ImageLayout();
        layout.setMinX(input.getMinX());
        layout.setMinY(input.getMinY());
        layout.setWidth(input.getWidth());
        layout.setHeight(input.getHeight());
        layout.setTileHeight(input.getTileHeight());
        layout.setTileWidth(input.getTileWidth());
        // Define rendering hints
        RenderingHints renderHints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout);
        // Create an image with all 255
        RenderedImage alpha = ConstantDescriptor.create(Float.valueOf(input.getWidth()),
                Float.valueOf(input.getHeight()), new Byte[] { (byte) 255 }, renderHints);
        // Format Image
        ImageWorker w = new ImageWorker(alpha).format(input.getSampleModel().getDataType());

        // Create the GridCoverage
        GridCoverage2D newCoverage = gridCoverageFactory.create(coverage.getName() + "Alpha",
                w.getRenderedImage(), coverage.getGridGeometry(), null,
                new GridCoverage2D[] { coverage }, coverage.getProperties());
        return newCoverage;
    }

    private GridCoverage2D displaceCoverage(GridCoverage2D coverage, double tx, double ty) {
        // let's compute the new grid geometry
        GridGeometry2D originalGG = coverage.getGridGeometry();
        GridEnvelope gridRange = originalGG.getGridRange();
        Envelope2D envelope = originalGG.getEnvelope2D();
        
        double minx = envelope.getMinX() + tx;
        double miny = envelope.getMinY() + ty;
        double maxx = envelope.getMaxX() + tx;
        double maxy = envelope.getMaxY() + ty;
        ReferencedEnvelope translatedEnvelope = new ReferencedEnvelope(minx, maxx, miny, maxy,
                envelope.getCoordinateReferenceSystem());

        GridGeometry2D translatedGG = new GridGeometry2D(gridRange, translatedEnvelope);

        GridCoverage2D translatedCoverage = gridCoverageFactory.create(coverage.getName(),
                coverage.getRenderedImage(), translatedGG, coverage.getSampleDimensions(),
                new GridCoverage2D[] { coverage }, coverage.getProperties());
        return translatedCoverage;
    }

    private double[] getTranslationFactors(Polygon reference, Polygon displaced) {
        // compare the two envelopes
        Envelope re = reference.getEnvelopeInternal();
        Envelope de = displaced.getEnvelopeInternal();
        double dw = Math.abs(re.getWidth() - de.getWidth());
        double dh = Math.abs(re.getHeight() - de.getHeight());
        if (dw > EPS * re.getWidth() || dh > EPS * re.getWidth()) {
            // this was not just a translation
            return null;
        }

        // compute the translation
        double dx = de.getMinX() - re.getMinX();
        double dy = de.getMinY() - re.getMinY();

        Polygon cloned = (Polygon) displaced.clone();
        cloned.apply(AffineTransformation.translationInstance(-dx, -dy));
        if (1 - new HausdorffSimilarityMeasure().measure(cloned, reference) > EPS) {
            return null;
        } else {
            return new double[] { dx, dy };
        }
    }

    /**
     * Paint this grid coverage. The caller must ensure that
     * <code>graphics</code> has an affine transform mapping "real world"
     * coordinates in the coordinate system given by {@link
     * #getCoordinateSystem}.
     * 
     * @param graphics
     *                the {@link Graphics2D} context in which to paint.
     * @param metaBufferedEnvelope
     * @throws Exception
     * @throws UnsupportedOperationException
     *                 if the transformation from grid to coordinate system in
     *                 the GridCoverage is not an AffineTransform
     */
    public void paint(
            final Graphics2D graphics,
            final GridCoverage2D gridCoverage, 
            final RasterSymbolizer symbolizer)
            throws Exception {
        paint(graphics, gridCoverage, symbolizer,null);
    }
    
    /**
     * Paint this grid coverage. The caller must ensure that
     * <code>graphics</code> has an affine transform mapping "real world"
     * coordinates in the coordinate system given by {@link
     * #getCoordinateSystem}.
     * 
     * @param graphics
     *                the {@link Graphics2D} context in which to paint.
     * @param metaBufferedEnvelope
     * @throws Exception
     * @throws UnsupportedOperationException
     *                 if the transformation from grid to coordinate system in
     *                 the GridCoverage is not an AffineTransform
     */
    public void paint(
            final Graphics2D graphics,
            final GridCoverage2D gridCoverage, 
            final RasterSymbolizer symbolizer,
            final double[] bkgValues)
            throws Exception {

        //
        // Initial checks
        //
        if(graphics==null){
            throw new NullPointerException(Errors.format(ErrorKeys.NULL_ARGUMENT_$1,"graphics"));
        }
        if(gridCoverage==null){
            throw new NullPointerException(Errors.format(ErrorKeys.NULL_ARGUMENT_$1,"gridCoverage"));
        }
        
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(new StringBuilder("Drawing coverage ").append(gridCoverage.toString()).toString());

        // Build the final image and the transformation
        RenderedImage finalImage = renderImage(gridCoverage, symbolizer, bkgValues);
        paintImage(graphics, finalImage);
    }

    /**
     * Paint the coverage read from the reader (using advanced projection handling). The caller must
     * ensure that <code>graphics</code> has an affine transform mapping "real world" coordinates in
     * the coordinate system given by {@link #getCoordinateSystem}.
     * 
     * @param graphics the {@link Graphics2D} context in which to paint.
     * @param metaBufferedEnvelope
     * @throws Exception
     * @throws UnsupportedOperationException if the transformation from grid to coordinate system in
     *         the GridCoverage is not an AffineTransform
     */
    public void paint(final Graphics2D graphics, final GridCoverage2DReader gridCoverageReader,
            GeneralParameterValue[] readParams, final RasterSymbolizer symbolizer,
            Interpolation interpolation, final Color background) throws Exception {

        //
        // Initial checks
        //
        if (graphics == null) {
            throw new NullPointerException(Errors.format(ErrorKeys.NULL_ARGUMENT_$1, "graphics"));
        }
        if (gridCoverageReader == null) {
            throw new NullPointerException(Errors.format(ErrorKeys.NULL_ARGUMENT_$1,
                    "gridCoverageReader"));
        }

        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(new StringBuilder("Drawing reader ").append(gridCoverageReader.toString())
                    .toString());

        // Build the final image and the transformation
        RenderedImage finalImage = renderImage(gridCoverageReader, readParams, symbolizer,
                interpolation, background);
        if (finalImage != null) {
            try {
                paintImage(graphics, finalImage);
            } finally {
                if (finalImage instanceof PlanarImage) {
                    ImageUtilities.disposePlanarImageChain((PlanarImage) finalImage);
                }
            }
        }
    }

    private void paintImage(final Graphics2D graphics, RenderedImage finalImage) {
        final RenderingHints oldHints = graphics.getRenderingHints();
        graphics.setRenderingHints(this.hints);

        try {
            // debug
            if (DEBUG) {
                writeRenderedImage(finalImage, "final");
            }

            // force solid alpha, the transparency has already been
            // dealt with in the image preparation, and we have to make
            // sure previous vector rendering code did not leave a non solid alpha
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

            // //
            // Drawing the Image
            // //
            graphics.drawRenderedImage(finalImage, GridCoverageRenderer.IDENTITY);

        } catch (Throwable t) {
            try {
                // log the error
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, t.getLocalizedMessage(), t);

                // /////////////////////////////////////////////////////////////
                // this is a workaround for a bug in Java2D, we need to convert
                // the image to component color model to make it work just fine.
                // /////////////////////////////////////////////////////////////
                if (t instanceof IllegalArgumentException) {
                    if (DEBUG) {
                        writeRenderedImage(finalImage, "preWORKAROUND1");
                    }
                    final RenderedImage image = new ImageWorker(finalImage)
                            .forceComponentColorModel(true).getRenderedImage();

                    if (DEBUG) {
                        writeRenderedImage(image, "WORKAROUND1");

                    }
                    graphics.drawRenderedImage(image, GridCoverageRenderer.IDENTITY);

                } else if (t instanceof ImagingOpException)
                // /////////////////////////////////////////////////////////////
                // this is a workaround for a bug in Java2D
                // (see bug 4723021
                // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4723021).
                //
                // AffineTransformOp.filter throws a
                // java.awt.image.ImagingOpException: Unable to tranform src
                // image when a PixelInterleavedSampleModel is used.
                //
                // CUSTOMER WORKAROUND :
                // draw the BufferedImage into a buffered image of type ARGB
                // then perform the affine transform. THIS OPERATION WASTES
                // RESOURCES BY PERFORMING AN ALLOCATION OF MEMORY AND A COPY ON
                // LARGE IMAGES.
                // /////////////////////////////////////////////////////////////
                {
                    BufferedImage buf = finalImage.getColorModel().hasAlpha() ? new BufferedImage(
                            finalImage.getWidth(), finalImage.getHeight(),
                            BufferedImage.TYPE_4BYTE_ABGR) : new BufferedImage(
                            finalImage.getWidth(), finalImage.getHeight(),
                            BufferedImage.TYPE_3BYTE_BGR);
                    if (DEBUG) {
                        writeRenderedImage(buf, "preWORKAROUND2");
                    }
                    final Graphics2D g = (Graphics2D) buf.getGraphics();
                    final int translationX = finalImage.getMinX(), translationY = finalImage
                            .getMinY();
                    g.drawRenderedImage(finalImage,
                            AffineTransform.getTranslateInstance(-translationX, -translationY));
                    g.dispose();
                    if (DEBUG) {
                        writeRenderedImage(buf, "WORKAROUND2");
                    }
                    GridCoverageRenderer.IDENTITY.concatenate(AffineTransform.getTranslateInstance(
                            translationX, translationY));
                    graphics.drawImage(buf, GridCoverageRenderer.IDENTITY, null);
                    // release
                    buf.flush();
                    buf = null;
                } else
                // log the error
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.fine("Unable to renderer this raster, no workaround found");

            } catch (Throwable t1) {
                // if the workaround fails again, there is really nothing to do
                // :-(
                LOGGER.log(Level.WARNING, t1.getLocalizedMessage(), t1);
            } finally {
                // ///////////////////////////////////////////////////////////////////
                //
                // Restore old hints
                //
                // ///////////////////////////////////////////////////////////////////
                graphics.setRenderingHints(oldHints);
            }
        }
    }

}
