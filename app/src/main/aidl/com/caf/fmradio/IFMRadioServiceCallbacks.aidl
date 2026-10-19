/*
 * Interface mirrored from the exported Qualcomm/CAF FMRadio service used by LineageOS.
 * Upstream: LineageOS android_vendor_qcom_opensource_fm-commonsys / fmapp2.
 */
package com.caf.fmradio;

interface IFMRadioServiceCallbacks
{
  void onEnabled();
  void onDisabled();
  void onRadioReset();
  void onTuneStatusChanged();
  void onProgramServiceChanged();
  void onRadioTextChanged();
  void onAlternateFrequencyChanged();
  void onSignalStrengthChanged();
  void onSearchComplete();
  void onSearchListComplete();
  void onMute(boolean bMuted);
  void onAudioUpdate(boolean bStereo);
  void onStationRDSSupported(boolean bRDSSupported);
  void onRecordingStopped();
  void onExtenRadioTextChanged();
  void onRecordingStarted();
  void onSeekNextStation();
  void onA2DPConnectionstateChanged(boolean state);
  void onFmAudioPathStarted();
  void onFmAudioPathStopped();
  void onExtenCountryCodeChanged();
  void getSigThCb(int val, int status);
  void getChDetThCb(int val, int status);
  void DefDataRdCb(int val, int status);
  void getBlendCb(int val, int status);
  void setChDetThCb(int status);
  void DefDataWrtCb(int status);
  void setBlendCb(int status);
  void getStationParamCb(int val, int status);
  void getStationDbgParamCb(int val, int status);
}
