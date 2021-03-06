/*
 * Copyright 2006-2020 The MZmine Development Team
 *
 * This file is part of MZmine.
 *
 * MZmine is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with MZmine; if not,
 * write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package io.github.mzmine.modules.dataprocessing.id_cliquems.cliquemsimplementation;


import com.google.common.collect.Range;
import io.github.mzmine.datamodel.DataPoint;
import io.github.mzmine.datamodel.PeakList;
import io.github.mzmine.datamodel.PeakListRow;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.modules.dataprocessing.id_cliquems.CliqueMSTask;
import io.github.mzmine.parameters.parametertypes.tolerances.MZTolerance;
import io.github.mzmine.parameters.parametertypes.tolerances.RTTolerance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.util.Pair;
import org.apache.commons.lang3.mutable.MutableDouble;

/**
 * This class contains all the data members and functions for finding cliques or groups using
 * CliqueMSR algorithm. Input is peakList and corresponding rawDatafile, and parameters to the
 * algorithm.
 */
public class ComputeCliqueModule {

  private final Logger logger = Logger.getLogger(getClass().getName());

  private final AnClique anClique;
  private final PeakList peakList;
  private final List<PeakData> peakDataList;
  private final RawDataFile rawDataFile;

  private final CliqueMSTask drivertask;

  //variables to update progress
  private final MutableDouble progress;

  //cosine correlation matrix calculated over columns of EIC matrix
  private double[][] cosineCorrelation;

  public ComputeCliqueModule(PeakList peakList, RawDataFile rdf, MutableDouble progress, CliqueMSTask task){
    this.rawDataFile = rdf;
    this.peakList =peakList;
    this.progress = progress;
    drivertask = task;
    peakDataList = getPeakDatafromPeaks(peakList,rdf);
    anClique = new AnClique(peakDataList,rdf);
  }

  /**
   * Extracts necessary data from rawdatafile
   * @param peakList feature lists
   * @param dataFile raw Data File
   * @return PeakData contains sufficient data for cliqueMS algorithm
   */
  private List<PeakData> getPeakDatafromPeaks(PeakList peakList, RawDataFile dataFile){
    List<PeakData> peakDataList = new ArrayList<>();
    for(int i=0;i<peakList.getRows().size() ; i++){
      PeakListRow peak = peakList.getRows().get(i);
      double mz ;
      double mzmin ;
      double mzmax ;
      double rt;
      double rtmin ;
      double rtmax ;
      double intensity;
      int peakListRowID;
      mz = peak.getPeak(dataFile).getMZ();
      mzmin  = peak.getPeak(dataFile).getRawDataPointsMZRange().lowerEndpoint();
      mzmax = peak.getPeak(dataFile).getRawDataPointsMZRange().upperEndpoint();
      rt = peak.getPeak(dataFile).getRT();
      rtmin = peak.getPeak(dataFile).getRawDataPointsRTRange().lowerEndpoint();
      rtmax = peak.getPeak(dataFile).getRawDataPointsRTRange().upperEndpoint();
      intensity = peak.getPeak(dataFile).getHeight();
      peakListRowID = peak.getID();
      PeakData peakData = new PeakData(mz,mzmin,mzmax,rt,rtmin,rtmax,intensity,i+1,peakListRowID);
      peakDataList.add(peakData);
    }
    return peakDataList;
  }

  /**
   * EIC matrix is a axb (a = #rts or #scans, b = #features) dimension matrix. The ith column
   * corresponds for the ith feature. For each column, the jth row value is calculated as follows -
   * 1) If j does not lies in the index of rt range of the ith feature, value is 0 (EIC[j][i] = 0)
   * 2) Else, the value is the mean intensities for the jth scan's datapoints' intensities (only
   *   those intensities whose corresponding mass in datapoint is in the mzRange of ith feature)
   *
   * EIC matrix is further used to calculate cosine similarity matrix
   * @param file raw data file
   * @param peakDataList contains peak data
   * @return double [][] EIC matrix
   */
  private double[][] getEIC(RawDataFile file, List<PeakData> peakDataList){
    List<List<DataPoint>> dataPoints = new ArrayList<>(); // contains m/z and intensity data
    List<Double> rts = new ArrayList<>(); // holds Retention Time values in seconds
    for(int z: file.getScanNumbers()){
      rts.add(file.getScan(z).getRetentionTime() * 60.0); // conversion for minutes to seconds
      List<DataPoint> dps = new ArrayList<DataPoint>(Arrays.asList(file.getScan(z).getDataPoints()));

      dataPoints.add(dps);
    }
    // nrows = #rts , ncols = # peaks, already transposed as in R code
    double EIC[][] = new double[file.getScanNumbers().length][peakDataList.size()];
    for(int i = 0; i<file.getScanNumbers().length ; i++){
      for(int j = 0; j<peakDataList.size();j++){
        EIC[i][j] = 0.0;
      }
    }

    for(int i=0; i<peakDataList.size() ; i++){
      if(drivertask.isCanceled()){
        return EIC;
      }
      PeakData pd = peakDataList.get(i);
      int posrtmin = rts.indexOf(pd.getRtmin() * 60.0); // position where peak matches rtmin
      int posrtmax = rts.indexOf(pd.getRtmax() * 60.0); // position where peak matches rtmax

      for(int j = posrtmin ; j<posrtmax ; j+=1){
        List<Double> intensities = new ArrayList<>();
        for(DataPoint dp : dataPoints.get(j)){
          Double mzmin = pd.getMzmin();
          Double mzmax = pd.getMzmax();
          Double dpmz =  dp.getMZ();
          if(dpmz<=mzmax && dpmz>=mzmin){
            intensities.add(dp.getIntensity());
          }
        }
        if(intensities.size() == 0){
          EIC[j][i] = 0.0; // no effect
        }
        else{
          Double meanInt = 0.0;
          for(Double d : intensities){
            meanInt+=d;
          }
          meanInt /= intensities.size();
          EIC[j][i] = meanInt;
        }


      }
//      progress update
      this.progress.setValue(drivertask.EIC_PROGRESS*((double)i/(double)peakDataList.size()));
    }

    return EIC;

  }

  //TODO make use of sparse matrix, make algo time efficient

  /**
   * Computes cosine correlation of data (EIC) matrix over the columns, so for ixj dimension EIC
   * matrix, the [x,y]th element of cosine correlation matrix contains the cosine similarity between
   * the xth and the yth column of EIC matrix, so cosine correlation matrix has dimension jxj
   *
   * @param data EIC matrix
   * @return cosine correlation matrix
   */
  private double[][] cosCorrbyColumn (double [][] data){
    int row = data.length, col = data[0].length;
    double [][] corr = new double[col][col];

    for(int i=0; i<col ; i++){
      for(int j=0; j<col; j++){
        corr[i][j] = 0.0;
      }
    }


    for(int i=0; i<col ; i++){
      for(int j=0; j<col; j++){
        if(drivertask.isCanceled()){
          return corr;
        }
        double modi = 0.0, modj = 0.0;
        for(int k=0;k<row;k++){
          corr[i][j] += data[k][i] * data[k][j];
          modi += data[k][i]* data[k][i];
          modj += data[k][j]* data[k][j];
        }
        modi = Math.sqrt(modi);
        modj = Math.sqrt(modj);
        corr[i][j] = corr[i][j]/(modi*modj);
      }
      //update progress
      this.progress.setValue(drivertask.EIC_PROGRESS + drivertask.MATRIX_PROGRESS*((double)(i+1)/(double)col));
    }
    return corr;
  }



  /**
   * identify peaks with very similar cosine correlation, m/z, rt and intensity
   * @param cosineCorr cosine correlation matrix
   * @param peakDataList contains features' information
   * @param mzdiff  tolerance value for mz
   * @param intdiff tolerance value for intensity
   * @param rtdiff tolerance value for rt
   * @return node ID of similar features
   */
  private List<Integer> similarFeatures(double[][] cosineCorr, List<PeakData> peakDataList, MZTolerance mzdiff, RTTolerance rtdiff,
      double  intdiff){
    //find all elements in cosineCorr with i<j and value > 0.99
    List<Integer> edgeX = new ArrayList<>();
    List<Integer> edgeY = new ArrayList<>();
    for(int i=0; i<cosineCorr.length; i++){
      for(int j=i+1; j<cosineCorr[0].length; j++){
        if(cosineCorr[i][j]>0.99){
          edgeX.add(i);
          edgeY.add(j);
        }
      }
    }
    List<Integer> nodesToDelete = new ArrayList<>();
    List<Integer> identicalNodes = new ArrayList<>();
    if(edgeX.size() > 0){
      for(int i=0; i<edgeX.size() ; i++){
        PeakData p1 = peakDataList.get(edgeX.get(i));
        PeakData p2 = peakDataList.get(edgeY.get(i));
        Range<Double> mz_Range = mzdiff.getToleranceRange(p1.getMz());
        Range<Double> rt_Range = rtdiff.getToleranceRange(p1.getRt());
        double error_int = Math.abs(p1.getIntensity() - p2.getIntensity()) / p1.getIntensity();
        if((mz_Range.contains(p2.getMz())) && (rt_Range.contains(p2.getRt())) && (error_int < intdiff)){
          Integer node = ( edgeX.get(i) < edgeY.get(i) ? edgeX.get(i) : edgeY.get(i)) ;
          identicalNodes.add((edgeX.get(i) >= edgeY.get(i) ? edgeX.get(i) : edgeY.get(i)));
          nodesToDelete.add(node);
        }
      }
    }

    HashMap<PeakData,PeakListRow> peakMap = new HashMap<>(); // map b/w peakData and peakListRow
    for(PeakData pd: peakDataList){
      for(PeakListRow row: peakList.getRows()){
        if(pd.getPeakListRowID() == row.getID()){
          peakMap.put(pd,row);
        }
      }
    }

    //annotate peakList for nodes to be deleted
    for(int i=0;i<nodesToDelete.size() ; i++){
      Integer nodeToDeleted = nodesToDelete.get(i);
      Integer nodeToReplace = identicalNodes.get(i);
      PeakData pdNodeToDelete = null;
      PeakData pdNodeToReplace = null;
      for(PeakData pd : peakDataList){
        if(nodeToDeleted.equals(pd.getNodeID())){
          pdNodeToDelete = pd;
          break;
        }
      }

      for(PeakData pd : peakDataList){
        if(nodeToReplace.equals(pd.getNodeID())){
          pdNodeToReplace = pd;
          break;
        }
      }

      PeakListRow row = peakMap.get(pdNodeToDelete);
      row.setComment("Similar to peak: "+pdNodeToReplace.getPeakListRowID());

    }


    Collections.sort(nodesToDelete);
    return nodesToDelete;
  }

  /**
   * Removes nodes that are too similar in rt, mz and intensity values
   * @param cosinus cosine correlation matrix
   * @param peakDL peak Data list
   * @param mzdiff tolerance values for similarity
   * @param rtdiff tolerance values for similarity
   * @param intdiff tolerance values for similarity
   */
  private void filterFeatures(double[][] cosinus, List<PeakData> peakDL, MZTolerance mzdiff, RTTolerance rtdiff,
      double  intdiff){
    List<PeakData> modifiedPeakDataList = new ArrayList<>();
    List<Integer> deleteIndices = similarFeatures(cosinus, peakDL, mzdiff, rtdiff, intdiff);
    if(deleteIndices.size()==0){
      logger.log(Level.FINEST,"No feature deleted");
      return;
    }

    //remove the peakdata containing
    for(PeakData pd : peakDataList){
      if(deleteIndices.contains(pd.getNodeID())){
        continue;
      }
      PeakData pdmod = new PeakData(pd);
      modifiedPeakDataList.add(pdmod);
    }

    double[][] modifiedCosineCorr = new double [cosinus.length - deleteIndices.size()][cosinus[0].length - deleteIndices.size()];
    //deleting row and columns of indices in deleteIndices
    int colShift = 0;


    for(int i=0; i<cosinus.length ; i++){
      int rowShift = 0;
      if(colShift < deleteIndices.size() && i==deleteIndices.get(colShift)){
        colShift++;
        continue;
      }
      for(int j=0; j<cosinus[0].length ; j++){
        if(rowShift < deleteIndices.size() &&  j==deleteIndices.get(rowShift)){
          rowShift++;
          continue;
        }
        modifiedCosineCorr[i-colShift][j-rowShift] = cosinus[i][j];
      }



    }



    this.cosineCorrelation = modifiedCosineCorr;
    anClique.changePeakDataList(modifiedPeakDataList);
    logger.log(Level.FINEST,deleteIndices.size()+" features deleted.");


  }

  /**
   * This function is to assign a clique group value to nodes that do not have links, because they
   * did not appear in the edgelists
   */
   private void updateCliques(){
    List<Pair<Integer,Integer>> nodeCliqueList = this.anClique.getNetwork().getResultNode_clique();
    List<PeakData> ungroupedFeatures = new ArrayList<>();
    Integer maxClique = 0;
    for(Pair nodeClique: nodeCliqueList){
      if(maxClique < (Integer) nodeClique.getKey()){
        maxClique = (Integer) nodeClique.getKey();
      }
    }
    for(PeakData pd : this.peakDataList){
      boolean present = false;
      for(Pair nodeClique : nodeCliqueList){
        if(nodeClique.getKey().equals(pd.getNodeID())){
          present = true;
          break;
        }
      }
      if(!present){
        ungroupedFeatures.add(pd);
      }
    }
    for(PeakData pd : ungroupedFeatures){
      maxClique+=1;
      Pair <Integer, Integer> p = new Pair<>(pd.getNodeID(),maxClique);
      nodeCliqueList.add(p);
    }

    Collections.sort(nodeCliqueList, (o1, o2) -> (int)(o1.getKey() - o2.getKey()));
   }

  /**
   * Driver function for calculating clique groups
   * @param filter filter similar features
   * @param mzdiff tolerance for mz similarity
   * @param rtdiff tolerance for rt similarity
   * @param intdiff tolerance for intensity similarity
   * @param tol tolerance for log likelihood function which is minimized for finding the clique.
   * @return AnClique object with calculated cliques.
   */
  public AnClique getClique(boolean filter, MZTolerance mzdiff, RTTolerance rtdiff, double  intdiff,
      double tol){

     if(anClique.cliquesFound){
       logger.log(Level.WARNING,"cliques have already been computed!");
     }
     double EIC[][] = getEIC(rawDataFile, peakDataList);

     if(drivertask.isCanceled()){
       return anClique;
     }

    this.cosineCorrelation = cosCorrbyColumn(EIC);
     if(drivertask.isCanceled()){
       return anClique;
     }
    if(filter)
      filterFeatures(cosineCorrelation, peakDataList, mzdiff, rtdiff, intdiff);


    List<Integer> nodeIDList = new ArrayList<>();
    for(PeakData pd : peakDataList)
      nodeIDList.add(pd.getNodeID());
    anClique.getNetwork().returnCliques(cosineCorrelation, nodeIDList, tol, false ,this.progress, this.drivertask);
    updateCliques();
    this.anClique.cliquesFound = true;
    this.anClique.computeCliqueFromResult();
    return this.anClique;
  }

  /**
   * return cliques using default parameter values taken from the R code
   *
   * @return AnClique object
   */
  public AnClique getClique() {
    return getClique(true, new MZTolerance(0,5),new RTTolerance(false,0.0001), 0.0001, .000001);
  }
}
