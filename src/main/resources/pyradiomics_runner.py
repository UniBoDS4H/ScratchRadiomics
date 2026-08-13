#!/usr/bin/env python3
"""
pyradiomics_runner.py
Bridge script between the ScratchRadiomics Fiji plugin and PyRadiomics.

Use:
    python pyradiomics_runner.py image.tif mask.tif params.yaml

Output:
    JSON on stdout con tutte le feature estratte, prefissate per famiglia.
    Es: {"firstorder_Energy": 1234.5, "glcm_Contrast": 0.87, ...}

Errori:
    Scritti su stderr, exit code != 0.
"""

import sys
import json
import traceback

def main():
    if len(sys.argv) < 4:
        print("Uso: python pyradiomics_runner.py image.tif mask.tif params.yaml",
              file=sys.stderr)
        sys.exit(1)

    image_path  = sys.argv[1]
    mask_path   = sys.argv[2]
    params_path = sys.argv[3]

    try:
        import radiomics
        from radiomics import featureextractor
    except ImportError as e:
        print(f"PyRadiomics not installed: {e}\n"
              f"Install with: pip install pyradiomics", file=sys.stderr)
        sys.exit(2)

    try:
        import logging
        logging.getLogger("radiomics").setLevel(logging.ERROR)

        extractor = featureextractor.RadiomicsFeatureExtractor(params_path)
        result    = extractor.execute(image_path, mask_path, label=1)

        features = {}
        for key, value in result.items():
            if key.startswith("diagnostics_"):
                continue
            try:
                features[str(key)] = float(value)
            except (TypeError, ValueError):
                pass

        print(json.dumps(features, indent=None, separators=(',', ':')))
        sys.exit(0)

    except Exception as e:
        print(f"Error during feature extraction: {e}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()