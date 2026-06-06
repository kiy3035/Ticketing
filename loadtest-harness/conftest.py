# pytest가 하네스 모듈(k6_runner 등)을 import할 수 있도록 이 디렉토리를 path에 추가
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
