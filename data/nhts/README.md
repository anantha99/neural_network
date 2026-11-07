# NHTS car-sharing rates

`sharing_2022.json` is computed by `bench/real/nhts.py` from the 2022 NextGen National
Household Travel Survey public use files (v2), U.S. Department of Transportation, Federal
Highway Administration, https://nhts.ornl.gov/ (public domain). The raw survey files are
not committed; download `https://nhts.ornl.gov/media/2022/download/csv.zip`, unzip it and run

    python -m bench.real.nhts --csv <folder> --out data/nhts/sharing_2022.json
