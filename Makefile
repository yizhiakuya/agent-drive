# Agent Drive 开发任务入口
.PHONY: help install dev-backend dev-frontend test bench build clean

help:
	@echo "Agent Drive 命令:"
	@echo "  make install       安装后端+前端依赖"
	@echo "  make dev-backend   启动后端 (uvicorn :8000)"
	@echo "  make dev-frontend  启动前端 (vite :5173)"
	@echo "  make test          运行全部测试 (单元+集成)"
	@echo "  make bench         真实 LLM 可靠性基准回归"
	@echo "  make build         前端生产构建"
	@echo "  make clean         清理缓存"

install:
	cd backend && pip install -r requirements.txt && pip install pytest
	cd frontend && npm install

dev-backend:
	cd backend && python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

dev-frontend:
	cd frontend && npm run dev -- --host 0.0.0.0

test:
	cd backend && python3 -m pytest tests/ -v
	cd backend && python3 tests/unit/test_agent.py
	cd backend && python3 tests/unit/test_critic.py
	cd backend && python3 tests/unit/test_reliability.py

bench:
	cd backend && python3 tests/integration/test_benchmark_real.py --repeat 3

build:
	cd frontend && npm run build

clean:
	find . -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
	rm -rf frontend/dist backend/.pytest_cache
