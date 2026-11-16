export interface EcgChannel {
  name: string;
  sampleRate: number | null;
  unit: string | null;
  calibrationSource: 'file' | 'user' | 'unknown';
  sampleCount: number;
  displayedSamples: number;
  durationSeconds: number | null;
  samples: (number | null)[];
  displaySamples?: (number | null)[];
  displayProcessing?: string[];
  automaticWindow?: number;
  parameterSources?: { samplingRate:string; unit:string };
  features?: { status:string; peakCount:number; peakIndices:number[]; medianSpacingSamples:number|null; medianSpacingMs:number|null; peakRatePerMinute:number|null; spacingVariationPercent:number|null; method:string; limitation:string };
  statistics: { median?: number; rms?: number; peakToPeak?: number; validPercent?: number; min: number; max: number; mean: number; standardDeviation: number; missingSamples: number; constant: boolean };
}

export interface EcgPreview {
  format: string;
  imageDataUrl?: string;
  channels: EcgChannel[];
  warnings: string[];
}

export interface EcgCalibration { samplingRate: number | null; unit: string | null; }

export interface EcgSelection { channelIndex:number; startSample:number; sampleCount:number; }
export interface EcgMarker { beatId:string; label:string; sample:number; }
export interface EcgInterval { beatId:string; label:string; startSample:number; endSample:number; durationMs:number|null; durationSamples?:number; }
export interface EcgBeat { id:string; rSample:number; measurements:Record<string,number|null>; }
export interface EcgMorphology {
  timeBasis?:'samples'; status:'estimated'|'unavailable'; reason:string|null; channelIndex:number; channelName:string;
  startSample:number; endSample:number; sampleRate:number|null; calibrationSource:string;
  method:string; limitation:string; preprocessing?:string; exclusions?:string[];
  markers:EcgMarker[]; intervals:EcgInterval[]; beats:EcgBeat[];
  summary:Record<string,{ median:number; min:number; max:number; count:number }|null>;
  quality:{ templateCorrelation?:number; completeBeats?:number; measuredBeats?:number; completePercent?:number; invertedForDetection?:boolean };
}
