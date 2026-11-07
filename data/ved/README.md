# VED trip table

`trips.csv.gz` is derived from the Vehicle Energy Dataset (VED):

> G. Oh, D. J. LeBlanc, H. Peng, "Vehicle Energy Dataset (VED), A Large-scale Dataset for
> Vehicle Energy Consumption Research", IEEE Transactions on Intelligent Transportation
> Systems, 2020. https://github.com/gsoh/VED

VED is licensed under the Apache License 2.0. This file reduces VED's per-second dynamic
data (about 3 GB) to one row per trip; the columns are documented in `bench/real/ved.py`.
Times are VED's clock (UTC); `bench/real/places.py` converts them to America/Detroit.
Trip ends were de-identified by the VED authors (fogging and cutting to major
intersections) before release.

Rebuild:

    git clone --depth 1 https://github.com/gsoh/VED.git
    # extract VED/Data/VED_DynamicData_Part1.7z and Part2.7z into one folder
    python -m bench.real.ved --raw <folder> --out data/ved/trips.csv.gz
