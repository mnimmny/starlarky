## Colab Local Larky
Replicating larky repl https://replit.com/@mahmoudimus/LocalLarkyDevelopmentRuntime
so that it's easy to utilize with Google's hosted jupyter notebook: colab.research.google.com 

Hopefully this makes it easier to construct example use cases in jupyternotebooks 

### Setup:
1. Clone repo: TODO consider updating to main repo

```
!git clone https://github.com/mnimmny/starlarky.git
```

2. set system path to local repo 

```import sys
import os
sys.path.insert(0,'/content/starlarky/')
os.chdir('./starlarky/colab-local-larky')
```

3. install poetry, run

```
!pip install poetry
!poetry install 
```

Example: Colab Notebook: https://colab.research.google.com/drive/10YXVqXaqzsWSsPHWm9eF5nHEu9CSfDHu#scrollTo=T2TgDBjA3b6E 
### Caveats
1. Colab's python version is 3.7.2, Repl requires i think python 3.8+

