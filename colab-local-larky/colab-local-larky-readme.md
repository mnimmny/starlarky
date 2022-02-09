## Local Larky
Replicating larky repl https://replit.com/@mahmoudimus/LocalLarkyDevelopmentRuntime
so that it's easy to utilize with Google's hosted jupyter notebook: colab.research.google.com 

Hopefully this makes it easier to construct example use cases in jupyternotebooks 

### Setup:
1. Clone repo - consider updating to main repo

```
!git clone https://github.com/mnimmny/starlarky/.git
```

2. set system path to local repo 

```import sys
import os
sys.path.insert(0,'/content/starlarky/')
os.chdir('./starlarky/colab-local-larky')```

4. install poetry, run

```
!pip install poetry
!poetry install 
```

### Caveats
1. Colab's python version 3.7.2
2. 
